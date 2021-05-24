package de.hpi.ddm.structures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BruteForceWorkPackage implements Serializable {
    private int passwordId;
    private String passwordChars;
    private String hint;
}
