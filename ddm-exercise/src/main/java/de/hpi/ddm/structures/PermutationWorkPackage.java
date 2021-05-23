package de.hpi.ddm.structures;

public class PermutationWorkPackage {
    private final char head;
    private final String passwordChars;

    public PermutationWorkPackage(final char head, final String passwordChars) {
        this.head = head;
        this.passwordChars = passwordChars;
    }

    public char getHead() {
        return head;
    }

    public String getPasswordChars() {
        return passwordChars;
    }
}
