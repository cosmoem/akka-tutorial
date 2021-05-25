package de.hpi.ddm.structures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermutationWorkPackage implements Serializable {
    private char head;
    private char head2;
    private String passwordChars;
}
