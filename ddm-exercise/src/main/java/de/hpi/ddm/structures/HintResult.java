package de.hpi.ddm.structures;

public class HintResult {
    final private int passwordId;
    final private char letter;
    final private String encodedHint;

    public HintResult(int passwordId, char letter, String encodedHint) {
        this.passwordId = passwordId;
        this.letter = letter;
        this.encodedHint = encodedHint;
    }

    public int getPasswordId() {
        return passwordId;
    }

    public char getLetter() {
        return letter;
    }

    public String getEncodedHint() {
        return encodedHint;
    }
}
