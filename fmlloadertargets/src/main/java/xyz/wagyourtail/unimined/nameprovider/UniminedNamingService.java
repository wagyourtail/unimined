package xyz.wagyourtail.unimined.nameprovider;

import cpw.mods.modlauncher.api.INameMappingService;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class UniminedNamingService implements INameMappingService {
    Map<String, String> classMappings = new HashMap<>();
    Map<String, String> methodMappings = new HashMap<>();
    Map<String, String> fieldMappings = new HashMap<>();

    public void initializeMappings() {
        String path = System.getProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp");
        if (path == null) {
            throw new RuntimeException("Could not find SRG-MCP mappings");
        }
        Path p = Paths.get(path);
        try (BufferedReader r = Files.newBufferedReader(p)) {
            String line = r.readLine();
            while (line != null) {
                String[] parts = line.split(" ");
                switch (parts[0]) {
                    case "CL:":
                        classMappings.put(parts[1], parts[2]);
                        break;
                    case "FD:": // FD: class/field namedclass/namedfield
//                        String cls = parts[1].substring(0, parts[1].lastIndexOf('/')).replace("/", ".");
                        String field = parts[1].substring(parts[1].lastIndexOf('/') + 1);
//                        String namedCls = parts[2].substring(0, parts[2].lastIndexOf('/')).replace("/", ".");
                        String namedField = parts[2].substring(parts[2].lastIndexOf('/') + 1);
                        fieldMappings.put(field, namedField);
                        break;
                    case "MD:": // MD: class/method nameddesc namedclass/namedmethod nameddesc
//                        cls = parts[1].substring(0, parts[1].lastIndexOf('/')).replace("/", ".");
                        String method = parts[1].substring(parts[1].lastIndexOf('/') + 1);
//                        namedCls = parts[3].substring(0, parts[3].lastIndexOf('/')).replace("/", ".");
                        String namedMethod = parts[3].substring(parts[3].lastIndexOf('/') + 1);
                        methodMappings.put(method, namedMethod);
                        break;
                }
                line = r.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String mappingName() {
        return "unimined";
    }

    @Override
    public String mappingVersion() {
        return "1.0";
    }

    @Override
    public Map.Entry<String, String> understanding() {
        return new Pair<>("srg", "unimined");
    }

    @Override
    public BiFunction<Domain, String, String> namingFunction() {
        return (domain, mapping) -> {
            switch (domain) {
                case CLASS:
                    return classMappings.getOrDefault(mapping, mapping);
                case FIELD:
                    return fieldMappings.getOrDefault(mapping, mapping);
                case METHOD:
                    return methodMappings.getOrDefault(mapping, mapping);
                default:
                    return mapping;
            }
        };
    }

    private class Pair<K, V> implements Map.Entry<K, V> {
        private final K key;
        private V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }


        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            V old = this.value;
            this.value = value;
            return old;
        }
    }
}
