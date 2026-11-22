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

    if host = "" or Len(code) <> 6 then
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
                if parsed.url <> ""
                    m.top.url = parsed.url
                    return
                end if
            end if
        end if

        ' The phone may not have started HLS yet. Retry without requiring
        ' the user to restart the Roku receiver.
        sleep(3000)
    end while
end sub
