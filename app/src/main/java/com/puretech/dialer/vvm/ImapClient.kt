package com.puretech.dialer.vvm

import android.net.Network
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal IMAP client for visual voicemail. Voicemails are stored on the carrier
 * IMAP server as e-mails: a text part plus an audio attachment (AMR/MP3). This
 * speaks just enough IMAP to LOGIN, SELECT INBOX, list UIDs, fetch the whole
 * message body, and flag messages read/deleted. Not a general-purpose IMAP library.
 *
 * Adapted from the OMTP IMAP handling in the AOSP Dialer (Apache-2.0): fetch the
 * full raw message (`BODY.PEEK[]`) and walk its real MIME structure for the
 * audio part (mime type starting with "audio/"), rather than guessing a fixed
 * part number from BODYSTRUCTURE —
 * carriers don't agree on where the audio part sits.
 *
 * [network], when non-null, is the transport (e.g. the carrier's own cellular
 * data, see [CellularNetwork]) the socket must be bound to -- some carriers'
 * VVM IMAP gateway is unreachable over the phone's default route (Wi-Fi).
 */
enum class ChangePinResult { OK, TOO_SHORT, TOO_LONG, TOO_WEAK, OLD_MISMATCH, INVALID_CHARS, UNKNOWN }

class ImapClient(private val cr: VvmCredentials, private val network: Network? = null) {

    private val TAG = "M5Vvm"

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var tagSeq = 0

    fun connect() {
        val net = network
        val s = if (net != null) {
            // Bind a plain socket to the requested transport (e.g. cellular)
            // before connecting, then wrap it for TLS if needed -- Network
            // only knows how to bind a not-yet-connected java.net.Socket.
            val plain = Socket()
            net.bindSocket(plain)
            plain.connect(java.net.InetSocketAddress(cr.host, cr.port), 30_000)
            if (cr.sslEnabled) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, cr.host, cr.port, true)
            } else {
                plain
            }
        } else if (cr.sslEnabled) {
            SSLSocketFactory.getDefault().createSocket(cr.host, cr.port)
        } else {
            Socket(cr.host, cr.port)
        }
        s.soTimeout = 30_000
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        readLine() // server greeting: "* OK ..."
        if (!cr.sslEnabled) upgradeToStartTlsIfOffered()
    }

    /**
     * Many VVM3/OMTP IMAP gateways (e.g. Verizon's cs1lv.imsvm.com, the
     * confirmed real host for this account) advertise plain port 143 but
     * reject LOGIN outright until STARTTLS has run -- confirmed 2026-08-03:
     * without this, login() got back a real `a1 NO command not allowed
     * LOGIN`, and a live Google Dialer capture on the same account showed
     * the identical CAPABILITY -> STARTTLS -> CAPABILITY -> LOGIN sequence
     * added here. No-op if the server doesn't advertise STARTTLS at all.
     */
    private fun upgradeToStartTlsIfOffered() {
        val caps = command("CAPABILITY")
        val offersStartTls = caps.untagged.any { it.contains("STARTTLS", ignoreCase = true) }
        if (!offersStartTls) return
        val resp = command("STARTTLS")
        require(resp.ok) { "IMAP STARTTLS failed: ${resp.status}" }
        val plain = socket ?: error("not connected")
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, cr.host, cr.port, true)
        tls.soTimeout = 30_000
        socket = tls
        input = BufferedInputStream(tls.getInputStream())
        output = tls.getOutputStream()
    }

    fun login() {
        val resp = command("LOGIN ${quote(cr.username)} ${quote(cr.password)}")
        require(resp.ok) { "IMAP LOGIN failed: ${resp.status}" }
    }

    fun selectInbox() {
        val resp = command("SELECT INBOX")
        require(resp.ok) { "IMAP SELECT failed: ${resp.status}" }
    }

    /** UIDs of every message in the inbox, oldest first. */
    fun listUids(): List<String> {
        val resp = command("UID SEARCH ALL")
        val line = resp.untagged.firstOrNull { it.startsWith("* SEARCH") } ?: return emptyList()
        return line.removePrefix("* SEARCH").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** Download one voicemail (headers + audio attachment) by UID, or null on failure. */
    fun fetchMessage(uid: String): VvmMessage? {
        val resp = command("UID FETCH $uid BODY.PEEK[]")
        if (!resp.ok) return null
        val raw = resp.literals.firstOrNull() ?: return null

        val text = raw.toString(Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n").let { if (it >= 0) it else text.indexOf("\n\n") }
        val headers = if (headerEnd >= 0) Mime.unfold(text.substring(0, headerEnd)) else ""

        val audio = Mime.findAudioPart(raw) ?: return null

        return VvmMessage(
            uid = uid,
            sender = parseSender(headers),
            dateMillis = parseDate(headers),
            durationSec = parseDuration(headers),
            mimeType = audio.mimeType,
            audio = audio.bytes
        )
    }

    fun markRead(uid: String) {
        runCatching { command("UID STORE $uid +FLAGS (\\Seen)") }
    }

    fun delete(uid: String) {
        runCatching {
            command("UID STORE $uid +FLAGS (\\Deleted)")
            command("EXPUNGE")
        }
    }

    /**
     * Changes the voicemail box PIN. Real, verified command -- ported from
     * AOSP's `ImapHelper.changePin()`/`Vvm3Protocol.getCommand()` (Apache-2.0):
     * VVM3 sends the raw command `CHANGE_TUI_PWD PWD=<new> OLD_PWD=<old>`
     * directly over the authenticated IMAP connection (not a standard IMAP
     * verb), and the carrier replies with a tagged OK/NO plus, on failure, one
     * of a small fixed set of error strings. Must be called after login().
     */
    fun changePin(oldPin: String, newPin: String): ChangePinResult {
        val resp = command("CHANGE_TUI_PWD PWD=$newPin OLD_PWD=$oldPin")
        if (resp.ok) return ChangePinResult.OK
        var msg = resp.status.substringAfter(' ', "").trim() // drop the tag
        msg = msg.removePrefix("NO").removePrefix("BAD").trim()
        if (msg.length >= 2 && msg.startsWith("\"") && msg.endsWith("\"")) {
            msg = msg.substring(1, msg.length - 1)
        }
        return when (msg.lowercase()) {
            "password too short" -> ChangePinResult.TOO_SHORT
            "password too long" -> ChangePinResult.TOO_LONG
            "password too weak" -> ChangePinResult.TOO_WEAK
            "old password mismatch" -> ChangePinResult.OLD_MISMATCH
            "password contains invalid characters" -> ChangePinResult.INVALID_CHARS
            else -> ChangePinResult.UNKNOWN
        }
    }

    /**
     * Uploads a custom greeting. Protocol confirmed 2026-08-04 by tracing the
     * real call chain in a decompiled build of Google's actual Play Store
     * Phone app (`RecordVoicemailGreetingActivity` ->
     * `GreetingChangerImpl` -> `ImapHelper.getMessageFromGreeting`/
     * `setDefaultVoicemailGreeting`, i.e. the same shared IMAP client class
     * used for the (Apache-2.0, already-verified) PIN-change and normal
     * voicemail-fetch paths -- this is a generic OMTP/CVVM-lineage mechanism
     * (Comverse/Synchronoss "CNS" extension headers), not VVM3-specific):
     * a real IMAP `APPEND` of a MIME message to the mailbox literally named
     * `"GREETINGS"` (not "Greetings"), carrying the `$CNS-Greeting-On`
     * keyword flag and a handful of `X-CNS-*` headers identifying it as a
     * greeting rather than a normal voicemail. The real client always
     * encodes as `audio/amr`; [mimeType] here reflects whatever [audio]
     * actually is (matters for a picked file that isn't AMR) rather than
     * mislabeling arbitrary bytes as AMR.
     */
    fun appendGreeting(audio: ByteArray, mimeType: String, durationSec: Long): Boolean {
        val ext = when {
            mimeType.contains("amr") -> "amr"
            mimeType.contains("wav") -> "wav"
            else -> "mp3"
        }
        val encoded = Base64.encodeToString(audio, Base64.NO_WRAP).chunked(76).joinToString("\r\n")
        val header = buildString {
            append("X-CNS-Message-Context: x-voice-greeting-message\r\n")
            append("X-CNS-Media-Size: x-voice-greeting=1msgs;\r\n")
            append("Importance: normal\r\n")
            append("X-CNS-Greeting-Type: normal-greeting\r\n")
            append("Content-Duration: $durationSec\r\n")
            append("Content-Type: $mimeType; name=\"greetings.$ext\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; size=\"${audio.size}\"; filename=\"greetings.$ext\"\r\n")
            append("\r\n")
        }
        val message = (header + encoded + "\r\n").toByteArray(Charsets.US_ASCII)
        // A literal argument ({n}) needs a "+ ..." continuation from the
        // server before the message bytes are sent -- command() isn't usable
        // here since readResponse() only stops at the final tagged line and
        // would just block forever waiting for a continuation that never
        // becomes a full response on its own.
        val tag = "a${++tagSeq}"
        val out = output ?: return false
        val cmdLine = "$tag APPEND \"GREETINGS\" (\$CNS-Greeting-On) {${message.size}}\r\n"
        Log.d(TAG, "appendGreeting: sending command (message ${message.size} bytes): ${cmdLine.trim()}")
        out.write(cmdLine.toByteArray(Charsets.US_ASCII))
        out.flush()
        val cont = readLine()
        Log.d(TAG, "appendGreeting: continuation response: $cont")
        if (cont == null || !cont.startsWith("+")) return false // e.g. rejected: no such mailbox
        out.write(message)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        Log.d(TAG, "appendGreeting: message bytes sent, awaiting final response")
        val resp = readResponse(tag)
        Log.d(TAG, "appendGreeting: final response: ${resp.status}")
        return resp.ok
    }

    fun close() {
        runCatching { command("LOGOUT") }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    // --- IMAP transport -------------------------------------------------------

    private data class Response(
        val status: String,
        val untagged: List<String>,
        val literals: List<ByteArray>
    ) {
        val ok get() = status.contains(" OK", ignoreCase = true)
    }

    private fun command(cmd: String): Response {
        val tag = "a${++tagSeq}"
        val out = output ?: error("not connected")
        out.write("$tag $cmd\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        return readResponse(tag)
    }

    // The unescaped trailing '}' compiles fine under desktop/standard Java
    // regex but throws PatternSyntaxException on this device's ICU-backed
    // regex engine (confirmed 2026-08-03 -- crashed the whole default-dialer
    // process the moment a real IMAP sync was first attempted, since this
    // field initializer runs in the ImapClient constructor with nothing
    // upstream catching it). Escape it explicitly; valid in both engines.
    private val literalRe = Regex("\\{(\\d+)\\}\\s*$")

    private fun readResponse(tag: String): Response {
        val untagged = ArrayList<String>()
        val literals = ArrayList<ByteArray>()
        while (true) {
            val line = readLine() ?: return Response("$tag NO timeout", untagged, literals)
            val m = literalRe.find(line)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                literals.add(readBytes(n))
                untagged.add(line)
                continue
            }
            if (line.startsWith("$tag ")) {
                return Response(line, untagged, literals)
            }
            untagged.add(line)
        }
    }

    private fun readLine(): String? {
        val inp = input ?: return null
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("US-ASCII")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("US-ASCII")
    }

    private fun readBytes(n: Int): ByteArray {
        val inp = input ?: return ByteArray(0)
        val out = ByteArrayOutputStream(n)
        var remaining = n
        val chunk = ByteArray(8192)
        while (remaining > 0) {
            val read = inp.read(chunk, 0, minOf(chunk.size, remaining))
            if (read == -1) break
            out.write(chunk, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // --- header parsing (best-effort) ------------------------------------------

    private fun parseSender(headers: String): String {
        val from = headerValue(headers, "From") ?: return ""
        // "From: 18005551234@vmail.carrier.com" or "From: \"x\" <num@host>"
        val at = from.substringAfterLast('<', from).substringBefore('@')
        return at.filter { it.isDigit() || it == '+' }.ifBlank {
            from.filter { it.isDigit() || it == '+' }
        }
    }

    private fun parseDate(headers: String): Long {
        val raw = headerValue(headers, "Date") ?: return System.currentTimeMillis()
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz"
        )
        for (p in patterns) {
            try {
                return java.text.SimpleDateFormat(p, java.util.Locale.US).parse(raw)?.time
                    ?: continue
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }

    private fun parseDuration(headers: String): Long {
        val raw = headerValue(headers, "Content-Duration")
            ?: headerValue(headers, "X-Content-Duration") ?: return 0L
        return raw.trim().toLongOrNull() ?: 0L
    }

    private fun headerValue(headers: String, name: String): String? {
        for (line in headers.split("\r\n", "\n")) {
            if (line.startsWith("$name:", ignoreCase = true)) {
                return line.substringAfter(':').trim()
            }
        }
        return null
    }
}

/**
 * Minimal recursive MIME parser used only to locate the audio part (mime type
 * starting with "audio/") of a
 * downloaded voicemail message. Walks the real part tree (handling nested
 * multipart/alternative under multipart/mixed, arbitrary part ordering, and
 * per-part Content-Transfer-Encoding) instead of assuming a fixed part number.
 * ISO-8859-1 is used for the byte<->String bridge deliberately: it maps every
 * byte 0-255 to one char 1:1, so string offsets equal byte offsets exactly,
 * which keeps the (binary-safe) audio bytes intact through the split.
 */
private object Mime {
    data class Part(val mimeType: String, val bytes: ByteArray)

    fun findAudioPart(raw: ByteArray, depth: Int = 0): Part? {
        if (depth > 4) return null
        val text = raw.toString(Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n").let { if (it >= 0) it else text.indexOf("\n\n") }
        if (headerEnd < 0) return null
        val headerText = unfold(text.substring(0, headerEnd))
        val bodyStart = if (text.startsWith("\r\n\r\n", headerEnd)) headerEnd + 4
        else if (text.startsWith("\n\n", headerEnd)) headerEnd + 2
        else headerEnd

        val contentType = header(headerText, "Content-Type") ?: "text/plain"
        val (mainType, params) = parseContentType(contentType)
        val encoding = header(headerText, "Content-Transfer-Encoding")?.trim()?.lowercase().orEmpty()

        if (mainType.startsWith("multipart/")) {
            val boundary = params["boundary"] ?: return null
            for (partBytes in splitOnBoundary(raw, bodyStart, boundary)) {
                findAudioPart(partBytes, depth + 1)?.let { return it }
            }
            return null
        }

        if (!mainType.startsWith("audio/")) return null
        val bodyBytes = raw.copyOfRange(bodyStart.coerceAtMost(raw.size), raw.size)
        return Part(mainType, decode(bodyBytes, encoding))
    }

    fun unfold(headerBlock: String): String {
        val out = StringBuilder()
        for (line in headerBlock.split("\r\n", "\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out.append(' ').append(line.trim())
            } else {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            }
        }
        return out.toString()
    }

    private fun header(unfolded: String, name: String): String? {
        for (line in unfolded.split('\n')) {
            if (line.startsWith("$name:", ignoreCase = true)) return line.substringAfter(':').trim()
        }
        return null
    }

    private fun parseContentType(value: String): Pair<String, Map<String, String>> {
        val parts = value.split(';')
        val mainType = parts.getOrElse(0) { "" }.trim().lowercase()
        val params = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val key = p.substring(0, eq).trim().lowercase()
            var v = p.substring(eq + 1).trim()
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            params[key] = v
        }
        return mainType to params
    }

    /** Splits [bodyStart, end) of [raw] on `--boundary` delimiter lines, returning
     *  each part's raw bytes (own headers + body), excluding the closing delimiter. */
    private fun splitOnBoundary(raw: ByteArray, bodyStart: Int, boundary: String): List<ByteArray> {
        val text = raw.toString(Charsets.ISO_8859_1)
        val delim = "--$boundary"
        val parts = ArrayList<ByteArray>()
        var start = text.indexOf(delim, bodyStart)
        if (start < 0) return parts
        while (true) {
            val afterDelim = start + delim.length
            if (text.startsWith("--", afterDelim)) break // closing boundary "--boundary--"
            var partStart = afterDelim
            if (text.startsWith("\r\n", partStart)) partStart += 2
            else if (text.startsWith("\n", partStart)) partStart += 1
            val next = text.indexOf(delim, partStart)
            if (next < 0) break
            var partEnd = next
            if (partEnd >= 2 && text.substring(partEnd - 2, partEnd) == "\r\n") partEnd -= 2
            else if (partEnd >= 1 && text[partEnd - 1] == '\n') partEnd -= 1
            if (partEnd > partStart) parts.add(raw.copyOfRange(partStart, partEnd))
            start = next
        }
        return parts
    }

    private fun decode(bytes: ByteArray, encoding: String): ByteArray = when (encoding) {
        "base64" -> try {
            val clean = bytes.toString(Charsets.US_ASCII).filter { !it.isWhitespace() }
            Base64.decode(clean, Base64.DEFAULT)
        } catch (_: Exception) {
            bytes
        }
        "quoted-printable" -> decodeQuotedPrintable(bytes)
        else -> bytes // 7bit/8bit/binary/unspecified: already raw
    }

    private fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '='.code.toByte()) {
                if (i + 2 < bytes.size && bytes[i + 1] == '\r'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) {
                    i += 3; continue // soft line break
                }
                if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) {
                    i += 2; continue
                }
                if (i + 2 < bytes.size) {
                    val hex = String(byteArrayOf(bytes[i + 1], bytes[i + 2]), Charsets.US_ASCII)
                    val v = hex.toIntOrNull(16)
                    if (v != null) {
                        out.write(v); i += 3; continue
                    }
                }
            }
            out.write(b.toInt()); i++
        }
        return out.toByteArray()
    }
}
