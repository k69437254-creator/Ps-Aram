#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
old_main='''function isValidStreamUrl(url as Dynamic) as Boolean
    if url = invalid then return false
    if Len(url) < 9 or Len(url) > 2048 then return false
    if Instr(1, url, " ") > 0 then return false

    if LCase(Left(url, 8)) = "https://" then return true
    if LCase(Left(url, 7)) = "http://" then return true

    return false
end function'''
old_pair='''function isValidStreamUrl(url as Dynamic) as Boolean
    if url = invalid then return false
    if Len(url) < 9 or Len(url) > 2048 then return false
    if Instr(1, url, " ") > 0 then return false

    scheme = LCase(Left(url, 8))
    if scheme <> "https://" and LCase(Left(url, 7)) <> "http://" then
        return false
    end if

    return true
end function'''
new='''function isValidStreamUrl(url as Dynamic) as Boolean
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
    if Instr(1, path, "/hls/") <> 1 then return false
    if Right(LCase(path), 11) <> "/index.m3u8" then return false
    return true
end function'''
def replace_one(rel,old):
    p=ROOT/rel
    if not p.is_file(): raise SystemExit("REFUSED: missing "+rel)
    s=p.read_text(encoding="utf-8")
    if 'Right(LCase(path), 11) <> "/index.m3u8"' in s: return None
    if old not in s: raise SystemExit("REFUSED: expected validator not found exactly in "+rel+"; no writes made")
    return p,s.replace(old,new)
def main():
    changes=[]
    for rel,old in [("receivers/roku/source/MainScene.brs",old_main),("receivers/roku/source/components/PairingTask.brs",old_pair)]:
        result=replace_one(rel,old)
        if result: changes.append(result)
    for p,s in changes:
        p.write_text(s,encoding="utf-8")
        print("UPDATED:",p.relative_to(ROOT))

    contract = ROOT/"receivers/roku/tests/receiver_contract.test.py"
    if contract.is_file():
        test_text = contract.read_text(encoding="utf-8")
        test_text = test_text.replace(
            """assert 'scheme <> "https://"' in pair
assert 'LCase(Left(url, 7)) <> "http://"' in pair""",
            """assert 'Instr(1, path, "/hls/") <> 1' in pair
assert 'Right(LCase(path), 11) <> "/index.m3u8"' in pair"""
        )
        contract.write_text(test_text,encoding="utf-8")
        print("UPDATED:",contract.relative_to(ROOT))
    print("ROKU_HLS_URL_HARDENING=PASS")
if __name__=="__main__": main()
