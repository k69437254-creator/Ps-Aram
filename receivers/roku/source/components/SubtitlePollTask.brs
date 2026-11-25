sub init()
    m.top.functionName = "run"
end sub

sub run()
    cursor = 0

    while m.top.control = "RUN"
        host = m.top.host
        code = m.top.code

        secure = false
        requestUrl = ""
        if host <> invalid and code <> invalid and host <> "" and isSixDigitCode(code)
            secure = Left(LCase(host), 6) = "wss://"
            if secure then
                authority = secureProxyAuthority(host)
                if authority <> invalid then
                    requestUrl = "https://" + authority + "/receiver/roku/subtitle"
                end if
            else if Instr(1, host, "://") = 0 and Instr(1, host, "/") = 0 and Instr(1, host, "?") = 0 and Instr(1, host, "#") = 0
                requestUrl = "http://" + host + ":8765/receiver/roku/subtitle"
            end if
        end if

        response = invalid
        if requestUrl <> ""
            transfer = CreateObject("roUrlTransfer")
            certificatesReady = true
            if secure then
                certificatesReady = transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
            end if
            if certificatesReady then
                transfer.SetUrl(requestUrl)
                transfer.SetRequest("POST")
                transfer.AddHeader("Content-Type", "application/json")
                transfer.SetConnectionTimeout(3000)

                body = FormatJson({
                    code: code,
                    after: cursor
                })

                response = transfer.PostFromString(body)
            end if

            if response <> invalid and response <> ""
                parsed = ParseJson(response)

                if parsed <> invalid and parsed.items <> invalid
                    items = parsed.items

                    for each item in items
                        if item.sequence <> invalid and item.packet <> invalid
                            sequence = item.sequence

                            if sequence > cursor
                                m.top.subtitle = FormatJson(item.packet)
                                cursor = sequence
                                sleep(350)
                            end if
                        end if
                    end for
                end if
            end if
        end if

        sleep(650)
    end while
end sub

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

function isSixDigitCode(code as Dynamic) as Boolean
    if code = invalid or Len(code) <> 6 then return false
    for i = 1 to 6
        if Instr(1, "0123456789", Mid(code, i, 1)) = 0 then return false
    end for
    return true
end function
