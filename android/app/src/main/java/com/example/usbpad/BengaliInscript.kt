package com.example.usbpad

/**
 * HID can only send key positions, not Unicode. So each Bengali character is turned into the
 * US-keyboard character on the same key of the Windows "Bangla - INSCRIPT" layout (KBDINBE2).
 * Set the PC to that layout (Win+Space) and Bengali typed on the phone comes out as Bengali on the PC.
 * Characters that are not in the table pass through unchanged.
 */
object BengaliInscript {
    private val MAP: Map<Char, String> = HashMap<Char, String>().apply {
        put('\u09CB', "a") // BENGALI VOWEL SIGN O
        put('\u0993', "A") // BENGALI LETTER O
        put('\u09AC', "y") // BENGALI LETTER BA
        put('\u09AE', "c") // BENGALI LETTER MA
        put('\u09A3', "C") // BENGALI LETTER NNA
        put('\u09CD', "d") // BENGALI SIGN VIRAMA
        put('\u0985', "D") // BENGALI LETTER A
        put('\u09BE', "e") // BENGALI VOWEL SIGN AA
        put('\u0986', "E") // BENGALI LETTER AA
        put('\u09BF', "f") // BENGALI VOWEL SIGN I
        put('\u0987', "F") // BENGALI LETTER I
        put('\u09C1', "g") // BENGALI VOWEL SIGN U
        put('\u0989', "G") // BENGALI LETTER U
        put('\u09AA', "h") // BENGALI LETTER PA
        put('\u09AB', "H") // BENGALI LETTER PHA
        put('\u0997', "i") // BENGALI LETTER GA
        put('\u0998', "I") // BENGALI LETTER GHA
        put('\u09B0', "j") // BENGALI LETTER RA
        put('\u09CE', "J") // BENGALI LETTER KHANDA TA
        put('\u0995', "k") // BENGALI LETTER KA
        put('\u0996', "K") // BENGALI LETTER KHA
        put('\u09A4', "l") // BENGALI LETTER TA
        put('\u09A5', "L") // BENGALI LETTER THA
        put('\u09B8', "m") // BENGALI LETTER SA
        put('\u09B6', "M") // BENGALI LETTER SHA
        put('\u09B2', "n") // BENGALI LETTER LA
        put('\u09A6', "o") // BENGALI LETTER DA
        put('\u09A7', "O") // BENGALI LETTER DHA
        put('\u099C', "p") // BENGALI LETTER JA
        put('\u099D', "P") // BENGALI LETTER JHA
        put('\u09CC', "q") // BENGALI VOWEL SIGN AU
        put('\u0994', "Q") // BENGALI LETTER AU
        put('\u09C0', "r") // BENGALI VOWEL SIGN II
        put('\u0988', "R") // BENGALI LETTER II
        put('\u09C7', "s") // BENGALI VOWEL SIGN E
        put('\u098F', "S") // BENGALI LETTER E
        put('\u09C2', "t") // BENGALI VOWEL SIGN UU
        put('\u098A', "T") // BENGALI LETTER UU
        put('\u09B9', "u") // BENGALI LETTER HA
        put('\u0999', "U") // BENGALI LETTER NGA
        put('\u09A8', "v") // BENGALI LETTER NA
        put('\u09C8', "w") // BENGALI VOWEL SIGN AI
        put('\u0990', "W") // BENGALI LETTER AI
        put('\u0982', "x") // BENGALI SIGN ANUSVARA
        put('\u0981', "X") // BENGALI SIGN CANDRABINDU
        put('\u09AD', "Y") // BENGALI LETTER BHA
        put('\u099A', ";") // BENGALI LETTER CA
        put('\u099B', ":") // BENGALI LETTER CHA
        put('\u09C3', "=") // BENGALI VOWEL SIGN VOCALIC R
        put('\u098B', "+") // BENGALI LETTER VOCALIC R
        put('\u09B7', "<") // BENGALI LETTER SSA
        put('\u0983', "_") // BENGALI SIGN VISARGA
        put('\u0964', ">") // DEVANAGARI DANDA
        put('\u09DF', "/") // BENGALI LETTER YYA
        put('\u09AF', "?") // BENGALI LETTER YA
        put('\u09A1', "[") // BENGALI LETTER DDA
        put('\u09A2', "{") // BENGALI LETTER DDHA
        put('\u09BC', "]") // BENGALI SIGN NUKTA
        put('\u099E', "}") // BENGALI LETTER NYA
        put('\u099F', "'") // BENGALI LETTER TTA
        put('\u09A0', "\"") // BENGALI LETTER TTHA
        put('\u09E6', "0") // BENGALI DIGIT ZERO
        put('\u09E7', "1") // BENGALI DIGIT ONE
        put('\u09E8', "2") // BENGALI DIGIT TWO
        put('\u09E9', "3") // BENGALI DIGIT THREE
        put('\u09EA', "4") // BENGALI DIGIT FOUR
        put('\u09EB', "5") // BENGALI DIGIT FIVE
        put('\u09EC', "6") // BENGALI DIGIT SIX
        put('\u09ED', "7") // BENGALI DIGIT SEVEN
        put('\u09EE', "8") // BENGALI DIGIT EIGHT
        put('\u09EF', "9") // BENGALI DIGIT NINE
        put('\u09DC', "[]") // RRA = DDA + nukta
        put('\u09DD', "{]") // RHA = DDHA + nukta
    }

    fun toKeys(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) sb.append(MAP[c] ?: c.toString())
        return sb.toString()
    }
}
