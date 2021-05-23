package de.hpi.ddm.structures;

public class PasswordWorkpackage {

    private final int id;
    private final String name;
    private final String passwordCharacters;
    private final int passwordLength;
    private final String password;
    private final String [] hints;

    public PasswordWorkpackage(
            final int id,
            final String name,
            final String passwordCharacters,
            final int passwordLength,
            final String password,
            final String[] hints
    ) {
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

    public String getPasswordCharacters() {
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
