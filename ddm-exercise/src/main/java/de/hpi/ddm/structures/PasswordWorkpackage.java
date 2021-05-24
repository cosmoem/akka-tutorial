package de.hpi.ddm.structures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasswordWorkpackage implements Serializable {
    private int id;
    private String name;
    private String passwordCharacters;
    private int passwordLength;
    private String password;
    private String [] hints;
}
