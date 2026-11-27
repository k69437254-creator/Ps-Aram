sub init()
    m.status = m.top.findNode("status")
    m.player = m.top.findNode("player")
    m.player.observeField("state", "onPlayerState")

    m.top.observeField("phoneHost", "startPairing")
    m.top.observeField("pairingCode", "startPairing")

    m.task = CreateObject("roSGNode", "PairingTask")
    m.task.observeField("url", "onStreamUrl")
    m.task.observeField("error", "onPairingError")

    m.subtitleLabel = m.top.findNode("subtitle")
    m.subtitleTimer = m.top.findNode("subtitleTimer")

    m.subtitleTimer.observeField("fire", "onSubtitleTimeout")

    m.subtitleTask = CreateObject("roSGNode", "SubtitlePollTask")
    m.subtitleTask.observeField("subtitle", "onSubtitlePacket")
end sub

sub startPairing()
    host = m.top.phoneHost
    code = m.top.pairingCode

    if host = invalid or code = invalid then return
    if host = "" then
        m.status.text = "أدخل عنوان IP الهاتف في receiver-config.json"
        return
    end if
    if not isSixDigitCode(code) then
        m.status.text = "رمز الاقتران يجب أن يتكون من 6 أرقام"
        return
    end if

    if m.task.state = "run" then return

    ' Stop any previous playback and subtitle polling before a new session.
    if m.player <> invalid then m.player.control = "stop"
    if m.subtitleTask <> invalid then m.subtitleTask.control = "STOP"
    if m.subtitleTimer <> invalid then m.subtitleTimer.control = "stop"
    if m.subtitleLabel <> invalid then m.subtitleLabel.text = ""

    ' Reset task outputs so a repeated URL/error can trigger field observers.
    m.task.url = ""
    m.task.error = ""

    m.status.text = "جارٍ طلب عنوان البث من الهاتف..."
    m.task.host = host
    m.task.code = code
    m.task.control = "RUN"
end sub

sub onStreamUrl()
    url = rewriteSecureHlsUrl(m.task.url, m.top.phoneHost)
    if not isValidStreamUrl(url) then
        m.status.text = "رابط بث HLS غير صالح"
        return
    end if

    content = CreateObject("roSGNode", "ContentNode")
    content.url = url
    content.streamFormat = "hls"
    if Left(LCase(url), 8) = "https://"
        content.HttpCertificatesFile = "common:/certs/ca-bundle.crt"
    end if

    m.player.content = content
    m.player.control = "play"
    m.status.text = "تم استلام بث الفيديو"

    m.subtitleTask.host = m.top.phoneHost
    m.subtitleTask.code = m.top.pairingCode
    m.subtitleTask.control = "RUN"
end sub

sub onPairingError()
    if m.task.error <> invalid and m.task.error <> ""
        m.status.text = m.task.error
    end if
end sub

sub onPlayerState()
    if m.player = invalid then return

    state = m.player.state
    if state = "error"
        m.status.text = "حدث خطأ أثناء تشغيل الفيديو"
    else if state = "finished"
        m.status.text = "انتهى البث"
        if m.subtitleTimer <> invalid then m.subtitleTimer.control = "stop"
        if m.subtitleLabel <> invalid then m.subtitleLabel.text = ""
        if m.subtitleTask <> invalid then m.subtitleTask.control = "STOP"
    else if state = "playing"
        m.status.text = "البث يعمل"
    else if state = "buffering"
        m.status.text = "جارٍ تحميل البث..."
    end if
end sub


sub onSubtitlePacket()
    raw = m.subtitleTask.subtitle
    if raw = invalid or raw = "" then return

    parsed = ParseJson(raw)
    if parsed = invalid then return

    if parsed.clear = true
        m.subtitleTimer.control = "stop"
        m.subtitleLabel.text = ""
        return
    end if

    if parsed.translation = invalid then return

    translated = parsed.translation.ToStr()
    if translated = "" then return

    m.subtitleLabel.text = translated
    m.subtitleTimer.control = "stop"
    m.subtitleTimer.control = "start"
end sub

sub onSubtitleTimeout()
    m.subtitleLabel.text = ""
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false

    if key = "Back" or key = "back" then
        if m.task <> invalid then
            m.task.control = "STOP"
        end if
        if m.player <> invalid then
            m.player.control = "stop"
        end if
        if m.subtitleTask <> invalid then
            m.subtitleTask.control = "STOP"
        end if
        if m.subtitleTimer <> invalid then
            m.subtitleTimer.control = "stop"
        end if
        m.subtitleLabel.text = ""
        m.status.text = "تم إيقاف العرض"
        return true
    end if

    return false
end function

function rewriteSecureHlsUrl(url as Dynamic, phoneHost as Dynamic) as Dynamic
    authority = secureProxyAuthority(phoneHost)
    if authority = invalid then return url
    if url = invalid or Len(url) > 2048 then return invalid
    if Left(LCase(url), 7) <> "http://" then return invalid

    remainder = Mid(url, 8)
    pathStart = Instr(1, remainder, "/")
    if pathStart < 2 then return invalid
    path = Mid(remainder, pathStart)
    if Left(LCase(path), 5) <> "/hls/" then return invalid
    if Right(LCase(path), 10) <> "index.m3u8" then return invalid
    if Instr(1, path, "?") > 0 or Instr(1, path, "#") > 0 then return invalid

    return "https://" + authority + path
end function

function secureProxyAuthority(endpoint as Dynamic) as Dynamic
    if endpoint = invalid then return invalid
    value = endpoint.ToStr()
    if Right(value, 1) = "/" then value = Left(value, Len(value) - 1)
    if Left(LCase(value), 6) <> "wss://" then return invalid

    authority = Mid(value, 7)
    if authority = "" then return invalid
    if Instr(1, authority, "/") > 0 or Instr(1, authority, "?") > 0 or Instr(1, authority, "#") > 0 or Instr(1, authority, "@") > 0 or Instr(1, authority, " ") > 0 or Instr(1, authority, Chr(9)) > 0 then return invalid
    return authority
end function

' PSLINK_ROKU_SCENE_VALIDATION_V1
function isSixDigitCode(code as Dynamic) as Boolean
    if code = invalid then return false
    if Len(code) <> 6 then return false

    for i = 1 to 6
        if Instr(1, "0123456789", Mid(code, i, 1)) = 0 then
            return false
        end if
    end for

    return true
end function

function isValidStreamUrl(url as Dynamic) as Boolean
    if url = invalid then return false
    if Len(url) < 20 or Len(url) > 2048 then return false

    lowerUrl = LCase(url)
    if Instr(1, url, " ") > 0 or Instr(1, url, Chr(9)) > 0 then return false
    if Instr(1, url, "@") > 0 or Instr(1, url, "?") > 0 or Instr(1, url, "#") > 0 then return false

    schemeLength = 0
    if Left(lowerUrl, 8) = "https://" then
        schemeLength = 8
    else if Left(lowerUrl, 7) = "http://" then
        schemeLength = 7
    else
        return false
    end if

    remainder = Mid(url, schemeLength + 1)
    pathStart = Instr(1, remainder, "/")
    if pathStart < 2 then return false
    authority = Left(remainder, pathStart - 1)
    if authority = "" then return false

    path = Mid(remainder, pathStart)
    lowerPath = LCase(path)
    ' Accept any HLS playlist path (.m3u8 or .m3u) under any path, not just /hls/index.m3u8
    if Right(lowerPath, 5) <> ".m3u8" and Right(lowerPath, 4) <> ".m3u" then return false
    return true
end function
