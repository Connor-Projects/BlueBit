package bluebit.fitcloud.native

/**
 * FIPS-197 AES-256 block cipher, encryption direction only.
 *
 * The FitCloud transport uses the same AES-encrypt call for both directions because
 * its keystream transform is an XOR with `E(key, IV)` (see [P6SessionCipher]), so the
 * decryption direction is never needed.
 *
 * Implemented per FIPS-197 (flat state matrix sec. 3.4, key expansion sec. 5.1,
 * round count 14 for AES-256) so that `commonMain` has no javax.crypto dependency and
 * the desktop test suite can run it against a published known-answer vector and a
 * javax.crypto oracle.
 */
object Aes256BlockCipher {

    const val BLOCK_SIZE = 16
    const val KEY_SIZE = 32
    private const val ROUND_COUNT = 14

    private const val WORDS = (ROUND_COUNT + 1) * 4 // 60 round-key words

    /**
     * S-box, generated arithmetically per FIPS-197 sec. 5.1.1: multiplicative inverse
     * in GF(2^8) followed by the affine bit-rotation cascade and the 0x63 XOR.
     */
    private val SBOX: IntArray = IntArray(256).also { sbox ->
        fun multiply(a: Int, b: Int): Int {
            var acc = 0
            var m = a
            var n = b
            while (n != 0) {
                if (n and 1 != 0) acc = acc xor m
                m = ((m shl 1) xor (if (m and 0x80 != 0) 0x1B else 0)) and 0xFF
                n = n ushr 1
            }
            return acc
        }

        for (value in 0..255) {
            var inverse = 0
            if (value != 0) {
                for (candidate in 1..255) {
                    if (multiply(value, candidate) == 1) {
                        inverse = candidate
                        break
                    }
                }
            }
            var v = inverse
            var rotated = inverse
            repeat(4) {
                rotated = ((rotated shl 1) or (rotated ushr 7)) and 0xFF
                v = v xor rotated
            }
            sbox[value] = v xor 0x63
        }
    }

    /** AES-256 round constants, 8th bit of the MSB byte (FIPS-197 sec. 5.2). */
    private val RCON = intArrayOf(1, 2, 4, 8, 16, 32, 64)

    /**
     * Encrypt one 16-byte [block] under the 32-byte [key]; returns a new 16-byte array.
     * Neither input is modified.
     */
    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "AES-256 key must be 32 bytes, was ${key.size}" }
        require(block.size == BLOCK_SIZE) { "AES block must be 16 bytes, was ${block.size}" }

        val roundKeys = IntArray(WORDS)
        expandKey(key, roundKeys)

        // State as a flat 16-byte array laid out like the input: index = row + 4*column.
        val state = block.copyOf()

        addRoundKey(state, roundKeys, 0)
        for (round in 1 until ROUND_COUNT) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, roundKeys, round * 4)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, roundKeys, ROUND_COUNT * 4)
        return state
    }

    private fun subBytes(state: ByteArray) {
        for (index in state.indices) {
            state[index] = SBOX[state[index].toInt() and 0xFF].toByte()
        }
    }

    /** In the flat layout, row r occupies indices r, r+4, r+8, r+12; rotate left by r. */
    private fun shiftRows(state: ByteArray) {
        val tmp = ByteArray(BLOCK_SIZE)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                tmp[row + 4 * column] = state[row + 4 * ((column + row) and 3)]
            }
        }
        System.arraycopy(tmp, 0, state, 0, BLOCK_SIZE)
    }

    /** MixColumns over each 4-byte column (FIPS-197 sec. 5.1.3, fixed matrix). */
    private fun mixColumns(state: ByteArray) {
        fun xtime(value: Int): Int {
            val shifted = ((value and 0xFF) shl 1) and 0x1FF
            return (shifted xor (if (shifted >= 0x100) 0x11B else 0)) and 0xFF
        }

        val column = ByteArray(4)
        for (index in 0 until 4) {
            column[0] = state[index * 4]
            column[1] = state[index * 4 + 1]
            column[2] = state[index * 4 + 2]
            column[3] = state[index * 4 + 3]
            val a0 = column[0].toInt() and 0xFF
            val a1 = column[1].toInt() and 0xFF
            val a2 = column[2].toInt() and 0xFF
            val a3 = column[3].toInt() and 0xFF
            state[index * 4] = (xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3).toByte()
            state[index * 4 + 1] = (a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3).toByte()
            state[index * 4 + 2] = (a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)).toByte()
            state[index * 4 + 3] = ((xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)).toByte()
        }
    }

    /** XOR round-key column [wordIndex] .. [wordIndex]+3 into the state. */
    private fun addRoundKey(state: ByteArray, roundKeys: IntArray, wordIndex: Int) {
        for (column in 0 until 4) {
            val word = roundKeys[wordIndex + column]
            for (row in 0 until 4) {
                val keyByte = (word ushr ((3 - row) * 8)) and 0xFF
                state[column * 4 + row] = (state[column * 4 + row].toInt() xor keyByte).toByte()
            }
        }
    }

    /** Key expansion for AES-256 (FIPS-197 sec. 5.2: Nk = 8, Nr = 14, 60 words). */
    private fun expandKey(key: ByteArray, roundKeys: IntArray) {
        for (word in 0 until 8) {
            roundKeys[word] = ((key[word * 4].toInt() and 0xFF) shl 24) or
                ((key[word * 4 + 1].toInt() and 0xFF) shl 16) or
                ((key[word * 4 + 2].toInt() and 0xFF) shl 8) or
                (key[word * 4 + 3].toInt() and 0xFF)
        }
        for (word in 8 until WORDS) {
            var temp = roundKeys[word - 1]
            if (word % 8 == 4) {
                temp = substituteWord(temp)
            }
            if (word % 8 == 0) {
                // RotWord (rotate the 4 bytes of the word left by one) then SubWord,
                // then XOR with the round constant in the MSB byte.
                val rotWord = ((temp shl 8) or (temp ushr 24))
                temp = substituteWord(rotWord) xor (RCON[word / 8 - 1] shl 24)
            }
            roundKeys[word] = roundKeys[word - 8] xor temp
        }
    }

    private fun substituteWord(word: Int): Int =
        ((SBOX[(word ushr 24) and 0xFF] shl 24) or
            (SBOX[(word ushr 16) and 0xFF] shl 16) or
            (SBOX[(word ushr 8) and 0xFF] shl 8) or
            SBOX[word and 0xFF])
}
