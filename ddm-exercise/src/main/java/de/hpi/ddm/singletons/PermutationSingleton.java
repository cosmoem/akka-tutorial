package de.hpi.ddm.singletons;

import java.util.*;

import static java.util.Collections.*;

public class PermutationSingleton {
    private static final List<String> permutations = synchronizedList(new ArrayList<>());

    public static List<String> getPermutations() {
        return permutations;
    }

    public static void addPermutation(String permutationsPart) {
        permutations.add(permutationsPart);
    }
}
