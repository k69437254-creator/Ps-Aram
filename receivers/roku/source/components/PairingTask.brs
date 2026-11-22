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

    transfer = CreateObject("roUrlTransfer")
    transfer.SetUrl("http://" + host + ":8765/receiver/roku/stream-url")
    transfer.SetRequest("POST")
    transfer.AddHeader("Content-Type", "application/json")

    body = FormatJson({ code: code })
    response = transfer.PostFromString(body)

    if response = invalid then
        m.top.error = "تعذر الاتصال بالهاتف؛ تحقق من الشبكة وعنوان IP"
        return
    end if

    parsed = ParseJson(response)
    if parsed = invalid or parsed.url = invalid then
        m.top.error = "رفض الهاتف الطلب أو لم يبدأ بث HLS بعد"
        return
    end if

    m.top.url = parsed.url
end sub
