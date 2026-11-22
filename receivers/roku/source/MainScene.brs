sub init()
    m.status = m.top.findNode("status")
    m.player = m.top.findNode("player")

    m.top.observeField("phoneHost", "startPairing")
    m.top.observeField("pairingCode", "startPairing")

    m.task = CreateObject("roSGNode", "PairingTask")
    m.task.observeField("url", "onStreamUrl")
    m.task.observeField("error", "onPairingError")
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
end sub

sub onPairingError()
    if m.task.error <> invalid
        m.status.text = m.task.error
    end if
end sub
