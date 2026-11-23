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

    while m.top.control = "RUN"
        transfer = CreateObject("roUrlTransfer")
        transfer.SetUrl("http://" + host + ":8765/receiver/roku/stream-url")
        transfer.SetRequest("POST")
        transfer.AddHeader("Content-Type", "application/json")
        transfer.SetConnectionTimeout(3000)

        body = FormatJson({ code: code })
        response = transfer.PostFromString(body)

        if response <> invalid and response <> ""
            parsed = ParseJson(response)

            if parsed <> invalid and parsed.url <> invalid
                if isValidStreamUrl(parsed.url)
                    m.top.url = parsed.url
                    return
                else
                    m.top.error = "الهاتف أعاد رابط بث غير صالح"
                    return
                end if
            end if
        end if

        ' The phone may not have started HLS yet. Retry without requiring
        ' the user to restart the Roku receiver.
        sleep(3000)
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
    if Len(url) < 9 or Len(url) > 2048 then return false
    if Instr(1, url, " ") > 0 then return false

    scheme = LCase(Left(url, 8))
    if scheme <> "https://" and LCase(Left(url, 7)) <> "http://" then
        return false
    end if

    return true
end function
