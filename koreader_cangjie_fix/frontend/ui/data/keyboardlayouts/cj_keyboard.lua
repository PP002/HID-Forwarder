--[[--
RIME-style Cangjie (倉頡) input method for KOReader.

Features:
1. Works with both on-screen VirtualKeyboard and physical/Bluetooth external keyboards
2. Shows Cangjie radicals after cursor while typing  e.g. [手日]
3. No auto-commit; Space (or Enter) confirms the highlighted candidate/phrase
4. Candidates shown after the radical code  e.g. [手日·1明2冐3暌…]
5. Number keys 1-9 select candidates directly; Left/Right cycle candidates
6. Hold Shift (or switch to Shift layer) to type literal uppercase English letters

--]]

local CangjieIME = require("ui/data/keyboardlayouts/new_cangjie_ime")
local util = require("util")
local _ = require("gettext")

-- Start with the English keyboard layout
local cj_keyboard = dofile("frontend/ui/data/keyboardlayouts/en_keyboard.lua")
local SETTING_NAME = "keyboard_cangjie_settings"

local code_map = dofile("frontend/ui/data/keyboardlayouts/cj_data.lua")
local settings = G_reader_settings:readSetting(SETTING_NAME, {show_candi=true})

-- Map lowercase keys (a-y; z is not a standard Cangjie key) to uppercase dictionary codes.
-- Both unshifted physical/Bluetooth keyboard presses and layer 2 virtual keys emit lowercase,
-- while Shift (uppercase A-Z) bypasses Cangjie and inserts English letters directly.
local CJ_KEY_MAP = {
    a="A", b="B", c="C", d="D", e="E", f="F", g="G", h="H", i="I",
    j="J", k="K", l="L", m="M", n="N", o="O", p="P", q="Q", r="R",
    s="S", t="T", u="U", v="V", w="W", x="X", y="Y",
}

-- Cangjie radical labels for QWERTY keys (standard Cangjie 5th-generation mapping)
-- Row 2: Q=手 W=田 E=水 R=口 T=廿 Y=卜 U=山 I=戈 O=人 P=心
-- Row 3: A=日 S=尸 D=木 F=火 G=土 H=竹 J=十 K=大 L=中
-- Row 4: Z=重 X=難 C=金 V=女 B=月 N=弓 M=一

-- Override layer 2 (Cangjie layer) keys with radical labels and lowercase chars
-- Row 2 (keys[2]): q w e r t y u i o p
cj_keyboard.keys[2][1][2] = { label = "手", "q", alt_label = "Q" }
cj_keyboard.keys[2][2][2] = { label = "田", "w", alt_label = "W" }
cj_keyboard.keys[2][3][2] = { label = "水", "e", alt_label = "E" }
cj_keyboard.keys[2][4][2] = { label = "口", "r", alt_label = "R" }
cj_keyboard.keys[2][5][2] = { label = "廿", "t", alt_label = "T" }
cj_keyboard.keys[2][6][2] = { label = "卜", "y", alt_label = "Y" }
cj_keyboard.keys[2][7][2] = { label = "山", "u", alt_label = "U" }
cj_keyboard.keys[2][8][2] = { label = "戈", "i", alt_label = "I" }
cj_keyboard.keys[2][9][2] = { label = "人", "o", alt_label = "O" }
cj_keyboard.keys[2][10][2] = { label = "心", "p", alt_label = "P" }

-- Row 3 (keys[3]): a s d f g h j k l
cj_keyboard.keys[3][1][2] = { label = "日", "a", alt_label = "A" }
cj_keyboard.keys[3][2][2] = { label = "尸", "s", alt_label = "S" }
cj_keyboard.keys[3][3][2] = { label = "木", "d", alt_label = "D" }
cj_keyboard.keys[3][4][2] = { label = "火", "f", alt_label = "F" }
cj_keyboard.keys[3][5][2] = { label = "土", "g", alt_label = "G" }
cj_keyboard.keys[3][6][2] = { label = "竹", "h", alt_label = "H" }
cj_keyboard.keys[3][7][2] = { label = "十", "j", alt_label = "J" }
cj_keyboard.keys[3][8][2] = { label = "大", "k", alt_label = "K" }
cj_keyboard.keys[3][9][2] = { label = "中", "l", alt_label = "L" }

-- Row 4 (keys[4]): z x c v b n m
cj_keyboard.keys[4][2][2] = { label = "重", "z", alt_label = "Z" }
cj_keyboard.keys[4][3][2] = { label = "難", "x", alt_label = "X" }
cj_keyboard.keys[4][4][2] = { label = "金", "c", alt_label = "C" }
cj_keyboard.keys[4][5][2] = { label = "女", "v", alt_label = "V" }
cj_keyboard.keys[4][6][2] = { label = "月", "b", alt_label = "B" }
cj_keyboard.keys[4][7][2] = { label = "弓", "n", alt_label = "N" }
cj_keyboard.keys[4][8][2] = { label = "一", "m", alt_label = "M" }

-- Chinese punctuation overrides on the , (row 3 col 10) and . (row 5 col 3) keys
cj_keyboard.keys[3][10][2] = {
    "、",
    north = "；",
    alt_label = "；",
    northeast = "（",
    northwest = "\u{201c}",
    east = "《",
    southeast = "「",
    south = "，",
    southwest = "」",
    west = "》",
}

cj_keyboard.keys[5][3][2] = {
    "。",
    north = "：",
    alt_label = "：",
    northeast = "）",
    northwest = "\u{201d}",
    east = "〉",
    southeast = "『",
    south = "·",
    southwest = "』",
    west = "〈",
}

-- ── wrapInputBox ───────────────────────────────────────────────────────────
-- Called by VirtualKeyboard when this keyboard layout is activated.
-- Returns an unwrap closure that restores the original methods.
local function wrapInputBox(inputbox)
    if inputbox._cj_wrapped then return end
    inputbox._cj_wrapped = true

    -- Per-inputbox IME instance so secondary dialogs (e.g. Find/Save As) do not
    -- overwrite an underlying TextEditor's preedit state.
    local ime = CangjieIME:new{
        code_map = code_map,
        key_map = CJ_KEY_MAP,
        show_candi_callback = function()
            return settings.show_candi
        end,
    }

    local function wrappedAddChars(box, char, orig_char)
        ime:handle_input(box, char)
    end

    local function wrappedDelChar(box)
        ime:handle_del(box)
    end

    local function wrappedRightChar(box)
        if ime.buf ~= "" then
            ime:handle_next_cand(box)
        else
            box.rightChar:raw_method_call()
        end
    end

    local function wrappedLeftChar(box)
        if ime.buf ~= "" then
            ime:handle_prev_cand(box)
        else
            box.leftChar:raw_method_call()
        end
    end

    -- Strip any active preedit hint when external callers read text (Save, reinit, etc.)
    local function wrappedGetText(box)
        if ime.hint_char_count > 0 and box.charlist and box.charpos then
            local hint_end = box.charpos - 1
            local hint_start = hint_end - ime.hint_char_count + 1
            if hint_start >= 1 and hint_end <= #box.charlist then
                local out = {}
                for i = 1, #box.charlist do
                    if i < hint_start or i > hint_end then
                        out[#out + 1] = box.charlist[i]
                    end
                end
                return table.concat(out)
            end
        end
        return box.getText:raw_method_call()
    end

    local function separate(box)
        ime:separate(box)
    end

    local function clear_stack()
        ime:clear_stack()
    end

    local wrappers = {}
    table.insert(wrappers, util.wrapMethod(inputbox, "addChars",         wrappedAddChars,  nil))
    table.insert(wrappers, util.wrapMethod(inputbox, "delChar",          wrappedDelChar,   nil))
    table.insert(wrappers, util.wrapMethod(inputbox, "rightChar",        wrappedRightChar, nil))
    table.insert(wrappers, util.wrapMethod(inputbox, "leftChar",         wrappedLeftChar,  nil))
    table.insert(wrappers, util.wrapMethod(inputbox, "getText",          wrappedGetText,   nil))

    -- Flush preedit on any event that repositions the cursor or resets/closes the box
    table.insert(wrappers, util.wrapMethod(inputbox, "delNextChar",      nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "delWord",          nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "delToStartOfLine", nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "delAll",           nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "undoLastDeletion", nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "clear",            nil, clear_stack))
    table.insert(wrappers, util.wrapMethod(inputbox, "upLine",           nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "downLine",         nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "goToStartOfLine",  nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "goToEndOfLine",    nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "goToHome",         nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "goToEnd",          nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "moveCursorToCharPos", nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "onTapTextBox",     nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "onHoldTextBox",    nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "onSwipeTextBox",   nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "unfocus",          nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "onCloseKeyboard",  nil, separate))
    table.insert(wrappers, util.wrapMethod(inputbox, "keyBack",          nil, separate))

    return function()
        if inputbox._cj_wrapped then
            ime:separate(inputbox)
            for _, w in ipairs(wrappers) do
                w:revert()
            end
            inputbox._cj_wrapped = nil
        end
    end
end

cj_keyboard.wrapInputBox = wrapInputBox

-- ── Settings menu ──────────────────────────────────────────────────────────

local function genMenuItems()
    return {
        {
            text = _("Show character candidates"),
            checked_func = function()
                return settings.show_candi
            end,
            callback = function()
                settings.show_candi = not settings.show_candi
                G_reader_settings:saveSetting(SETTING_NAME, settings)
            end,
        },
    }
end

cj_keyboard.genMenuItems = genMenuItems

return cj_keyboard
