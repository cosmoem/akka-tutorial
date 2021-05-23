package de.hpi.ddm.singletons;

import java.util.HashMap;
import java.util.Map;

public class PermutationSingleton {
    private static Map<String, String> permutations = new HashMap<>();

    public static Map<String, String> getPermutations() {
        return permutations;
    }

    public static void setPermutations(Map<String, String> permutations) {
        PermutationSingleton.permutations = permutations;
    }
}
