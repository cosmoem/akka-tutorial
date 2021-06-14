package de.hpi.ddm.structures;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HintResult implements Serializable {
    private static final long serialVersionUID = 29876164436256001L;
    private int passwordId;
    private char letter;
    private String encodedHint;
}
