sub init()
    m.top.functionName = "run"
end sub

sub run()
    host = m.top.host
    code = m.top.code

    if host = invalid or code = invalid then
        m.top.error = "بيانات الهاتف أو رمز الاقتران غير موجودة"
        return
    end if

    if host = "" or not isSixDigitCode(code) then
        m.top.error = "تحقق من عنوان IP ورمز الاقتران"
        return
    end if

    retryCount = 0
    maxRetries = 5
    backoffMs = 3000

    while m.top.control = "RUN" and retryCount < maxRetries
        ws = CreateObject("roWebSocket")
        msgPort = CreateObject("roMessagePort")
        ws.SetMessagePort(msgPort)

        if not ws.SetUrl("ws://" + host + ":8765/subtitle") then
            m.top.error = "عنوان WebSocket للهاتف غير صالح"
            return
        end if

        if not ws.Open(10000) then
            ws.Close()
            retryCount = retryCount + 1
            if retryCount < maxRetries
                sleep(backoffMs)
                backoffMs = backoffMs * 2
                if backoffMs > 12000 then backoffMs = 12000
            end if
            continue
        end if

        deviceInfo = CreateObject("roDeviceInfo")
        receiverSessionId = "roku-" + deviceInfo.GetChannelClientId()
        authMsg = FormatJson({
            v: 2,
            kind: "auth",
            code: code,
            client: "PS Link Roku",
            sessionId: receiverSessionId
        })
        ws.Send(authMsg)

        responseStart = roTimespan()
        responseStart.mark()
        gotReady = false
        while m.top.control = "RUN" and responseStart.TotalSeconds() < 10
            msg = wait(200, msgPort)
            if type(msg) = "roWebSocketEvent"
                eventType = msg.GetType()
                if eventType = 5
                    info = msg.GetInfo()
                    if info <> invalid and info.Text <> invalid
                        response = ParseJson(info.Text)
                        if response <> invalid and response.v = 2
                            if response.kind = "ready"
                                gotReady = true
                            else if response.kind = "stream_url" and gotReady
                                url = response.url
                                if isValidStreamUrl(url)
                                    m.top.url = url
                                    ws.Close()
                                    return
                                end if
                                m.top.error = "الهاتف أعاد رابط بث غير صالح"
                                ws.Close()
                                return
                            else if response.kind = "error"
                                m.top.error = response.message
                                ws.Close()
                                return
                            end if
                        end if
                    end if
                else if eventType = 2 or eventType = 3
                    exit while
                end if
            end if
        end while

        ws.Close()
        retryCount = retryCount + 1
        if retryCount < maxRetries and m.top.control = "RUN"
            sleep(backoffMs)
            backoffMs = backoffMs * 2
            if backoffMs > 12000 then backoffMs = 12000
        end if
    end while

    if m.top.control = "RUN" and m.top.error = invalid
        m.top.error = "تعذر الحصول على رابط بث HLS من الهاتف"
    end if
end sub

function isSixDigitCode(code as Dynamic) as Boolean
    if code = invalid or Len(code) <> 6 then return false

    for i = 1 to 6
        if Instr(1, "0123456789", Mid(code, i, 1)) = 0 then return false
    end for

    return true
end function

function isValidStreamUrl(url as Dynamic) as Boolean
    if url = invalid or Len(url) < 20 or Len(url) > 2048 then return false

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

    lowerPath = LCase(Mid(remainder, pathStart))
    if Right(lowerPath, 5) <> ".m3u8" and Right(lowerPath, 4) <> ".m3u" then return false
    return true
end function
