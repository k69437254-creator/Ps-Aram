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
        ' PSLINK_ROKU_WS_V1 — Use WebSocket like other platforms (Tizen/webOS/Xbox Edge/Android)
        ' roWebSocket API (Roku OS 9.4+): CreateObject("roWebSocket"), connect(url), sendText(text),
        ' close(), getConnectionState(), setMessagePort(port). Messages arrive via roMessagePort.
        ws = CreateObject("roWebSocket")

        ' Set up message port for receiving WebSocket messages
        msgPort = CreateObject("roMessagePort")
        ws.setMessagePort(msgPort)

        ' Connect to the WebSocket server
        ws.connect("ws://" + host + ":8765/subtitle")

        ' Wait for connection with timeout (poll getConnectionState())
        waitStart = roTimespan()
        waitStart.mark()
        connected = false
        while not connected and waitStart.TotalSeconds() < 10
            state = ws.getConnectionState()
            if state = 1  ' CONNECTED
                connected = true
            else if state = 2  ' CLOSED - connection failed
                exit while
            end if
            sleep(200)
        end while

        if not connected
            ws.close()
            retryCount = retryCount + 1
            if retryCount < maxRetries
                sleep(backoffMs)
                backoffMs = backoffMs * 2
                if backoffMs > 12000 then backoffMs = 12000
            end if
            continue
        end if

        ' Send auth frame
        authMsg = "{ "v": 2, "kind": "auth", "code": "" + code + "", "client": "PS Link Roku" }"
        ws.sendText(authMsg)

        ' Wait for ready or stream_url messages via message port
        waitStart.mark()
        gotResponse = false
        while not gotResponse and waitStart.TotalSeconds() < 10
            ' Check for waiting messages (non-blocking)
            if msgPort.IsMessageWaiting()
                msg = msgPort.GetMessage()
                if type(msg) = "roAssociativeArray"
                    kind = msg.kind
                    if kind = "ready"
                        ' Request media config to trigger stream_url from phone
                        cfgMsg = "{ "v": 2, "kind": "media_config", "width": 1920, "height": 1080, "codec": 0, "fps": 60 }"
                        ws.sendText(cfgMsg)
                    else if kind = "stream_url"
                        url = msg.url
                        if url <> invalid
                            if isValidStreamUrl(url)
                                m.top.url = url
                                ws.close()
                                return
                            else
                                m.top.error = "الهاتف أعاد رابط بث غير صالح"
                                ws.close()
                                return
                            end if
                        end if
                    else if kind = "error"
                        m.top.error = msg.message
                        ws.close()
                        return
                    end if
                end if
                ' Clear remaining messages
                while msgPort.IsMessageWaiting()
                    msgPort.GetMessage()
                end while
            end if
            sleep(200)
        end while

        ws.close()

        ' The phone may not have started HLS yet. Retry without requiring
        ' the user to restart the Roku receiver.
        retryCount = retryCount + 1
        if retryCount < maxRetries
            sleep(backoffMs)
            backoffMs = backoffMs * 2
            if backoffMs > 12000 then backoffMs = 12000
        end if
    end while
end sub

' PSLINK_ROKU_INPUT_VALIDATION_V1
function isSixDigitCode(code as Dynamic) as Boolean
    if code = invalid then return false
    if Len(code) <> 6 then return false

    for i = 1 to 6
        digit = Mid(code, i, 1)
        if Instr(1, "0123456789", digit) = 0 then
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
    ' Accept any HLS playlist path (.m3u8 or .m3u) under any path
    if Right(lowerPath, 5) <> ".m3u8" and Right(lowerPath, 4) <> ".m3u" then return false
    return true
end function
