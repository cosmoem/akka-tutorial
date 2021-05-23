package de.hpi.ddm.structures;

public class BruteForceWorkPackage {
    final private int passwordId;
    final private String passwordChars;
    final private String hint;

    public BruteForceWorkPackage(int passwordId, String passwordChars, String hint) {
        this.passwordId = passwordId;
        this.passwordChars = passwordChars;
        this.hint = hint;
    }

    public int getPasswordId() {
        return passwordId;
    }

    public String getHint() {
        return hint;
    }

    public String getPasswordChars() {
        return passwordChars;
    }
}
