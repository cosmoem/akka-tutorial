package de.hpi.ddm.structures;

public class PasswordWorkpackage {

    private int id;
    private String name;
    private char [] passwordCharacters;
    private int passwordLength;
    private String password;
    private String [] hints;

    public PasswordWorkpackage(int id, String name, char [] passwordCharacters, int passwordLength, String password, String[] hints) {
        this.id = id;
        this.name = name;
        this.passwordCharacters = passwordCharacters;
        this.passwordLength = passwordLength;
        this.password = password;
        this.hints = hints;
    }


    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public char[] getPasswordCharacters() {
        return passwordCharacters;
    }

    public int getPasswordLength() {
        return passwordLength;
    }

    public String getPassword() {
        return password;
    }

    public String[] getHints() {
        return hints;
    }
}
