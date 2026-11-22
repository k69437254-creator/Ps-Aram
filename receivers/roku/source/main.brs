sub Main()
    configText = ReadAsciiFile("pkg:/source/receiver-config.json")
    config = ParseJson(configText)

    if config = invalid then
        print "PS Link: receiver-config.json is invalid"
        return
    end if

    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)

    scene = screen.CreateScene("MainScene")
    scene.phoneHost = config.phoneHost
    scene.pairingCode = config.pairingCode

    screen.Show()

    while true
        msg = Wait(0, port)
        if msg <> invalid
            if Type(msg) = "roSGScreenEvent"
                if msg.IsScreenClosed() then return
            end if
        end if
    end while
end sub
