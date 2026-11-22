sub init()
    m.top.functionName = "run"
end sub

sub run()
    cursor = 0

    while m.top.control = "RUN"
        host = m.top.host
        code = m.top.code

        if host <> invalid and code <> invalid and host <> "" and Len(code) = 6
            transfer = CreateObject("roUrlTransfer")
            transfer.SetUrl("http://" + host + ":8765/receiver/roku/subtitle")
            transfer.SetRequest("POST")
            transfer.AddHeader("Content-Type", "application/json")
            transfer.SetConnectionTimeout(3000)

            body = FormatJson({
                code: code,
                after: cursor
            })

            response = transfer.PostFromString(body)

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
