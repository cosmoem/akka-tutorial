package de.hpi.ddm.singletons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class PermutationSingleton {
    private static Map<String, String> permutations = new HashMap<>();

    public static Map<String, String> getPermutations() {
        return permutations;
    }

    public static String getPermutation(int index) {
        ArrayList<String> keys = new ArrayList<>(permutations.keySet());
        String key = keys.get(index);
        Map<String, String> permutation = new HashMap<>();
        return permutation.put(key, permutations.get(key));
    }

    public static void setPermutations(Map<String, String> permutations) {
        PermutationSingleton.permutations = permutations;
    }
}
