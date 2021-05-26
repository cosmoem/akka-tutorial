package de.hpi.ddm.singletons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PermutationSingleton {
    private static List<String> permutations = new ArrayList<>();

    public static List<String> getPermutations() {
        return permutations;
    }

    public static void addPermutations(List<String> permutationsPart) {
        permutations.addAll(permutationsPart);
    }
}
