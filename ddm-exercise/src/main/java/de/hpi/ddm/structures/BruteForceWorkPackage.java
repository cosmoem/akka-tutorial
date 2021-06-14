package de.hpi.ddm.structures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BruteForceWorkPackage implements Serializable {
    private static final long serialVersionUID = -12975316443837400L;
    private int passwordId;
    private String passwordChars;
    private String hint;
}
