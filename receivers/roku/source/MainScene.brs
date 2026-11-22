sub init()
    m.status = m.top.findNode("status")
    m.player = m.top.findNode("player")

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
    if host = "" or host = "CHANGE_TO_PHONE_IP" then
        m.status.text = "عدّل عنوان IP الهاتف في receiver-config.json"
        return
    end if
    if Len(code) <> 6 then
        m.status.text = "عدّل رمز الاقتران في receiver-config.json"
        return
    end if

    if m.task.control = "RUN" then return

    m.status.text = "جارٍ طلب عنوان البث من الهاتف..."
    m.task.host = host
    m.task.code = code
    m.task.control = "RUN"
end sub

sub onStreamUrl()
    url = m.task.url
    if url = invalid or url = "" then return

    content = CreateObject("roSGNode", "ContentNode")
    content.url = url
    content.streamFormat = "hls"

    m.player.content = content
    m.player.control = "play"
    m.status.text = "تم استلام بث الفيديو"

    m.subtitleTask.host = m.top.phoneHost
    m.subtitleTask.code = m.top.pairingCode
    m.subtitleTask.control = "RUN"
end sub

sub onPairingError()
    if m.task.error <> invalid
        m.status.text = m.task.error
    end if
end sub


sub onSubtitlePacket()
    raw = m.subtitleTask.subtitle
    if raw = invalid or raw = "" then return

    parsed = ParseJson(raw)
    if parsed = invalid then return
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
        m.subtitleLabel.text = ""
        m.status.text = "تم إيقاف العرض"
        return true
    end if

    return false
end function
