package de.hpi.ddm.structures;

public class HintResult {
    final private int passwordId;
    final private char letter;
    final private String decodedHint;

    public HintResult(int passwordId, char letter, String decodedHint) {
        this.passwordId = passwordId;
        this.letter = letter;
        this.decodedHint = decodedHint;
    }

    public int getPasswordId() {
        return passwordId;
    }

    public char getLetter() {
        return letter;
    }

    public String getDecodedHint() {
        return decodedHint;
    }
}
