package de.hpi.ddm.structures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordWorkPackage implements Serializable {
    private static final long serialVersionUID = 24684875323217333L;
    private int id;
    private String name;
    private String passwordCharacters;
    private int passwordLength;
    private String password;
    private String [] hints;
}
