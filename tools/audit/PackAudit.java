import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * M0 static pack audit for Rustic Craft 2.
 *
 * <p>Scans every jar in a mods directory (read-only) and emits JSON evidence:
 * <ul>
 *   <li>mods-index.json — jar file -> declared mod ids (from neoforge.mods.toml / mods.toml)</li>
 *   <li>block-tags.json — every data/&#42;/tags/block/&#42;.json entry with parsed values, per jar</li>
 *   <li>ore-candidates.json — block ids whose path looks ore-like (blockstates + block loot tables)</li>
 *   <li>recipe-results.json — every recipe result item id, per namespace</li>
 *   <li>item-models.json — every item model name per namespace (proxy for registered items)</li>
 *   <li>namespace-index.json — all data namespaces found, per jar</li>
 * </ul>
 * Usage: java PackAudit.java &lt;modsDir&gt; &lt;outDir&gt; [bicJar]
 */
public class PackAudit {

    private static final Pattern MOD_ID = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TAG_VALUE = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern RECIPE_RESULT = Pattern.compile("\"result\"\\s*:");
    private static final Pattern RECIPE_RESULT_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RECIPE_RESULT_ITEM = Pattern.compile("\"item\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RECIPE_RESULT_COUNT = Pattern.compile("\"result\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ORE_LIKE = Pattern.compile("ore|mineral", Pattern.CASE_INSENSITIVE);
    private static final Pattern NS_PATH = Pattern.compile("^(?:data|assets)/([^/]+)/");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PackAudit <modsDir> <outDir> [jarNameFilter]");
            System.exit(2);
        }
        Path modsDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        String filter = args.length > 2 ? args[2] : null;
        Files.createDirectories(outDir);

        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> filter == null || p.getFileName().toString().toLowerCase().contains(filter.toLowerCase()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(jars::add);
        }
        System.out.println("scanning " + jars.size() + " jars in " + modsDir);

        Map<String, List<String>> modIds = new TreeMap<>();
        Map<String, List<String>> namespaces = new TreeMap<>();
        // tagId -> (value -> jars contributing)
        Map<String, Map<String, List<String>>> blockTags = new TreeMap<>();
        Map<String, List<String>> oreCandidates = new TreeMap<>();
        Map<String, List<String>> recipeResults = new TreeMap<>();
        Map<String, List<String>> itemModels = new TreeMap<>();
        Map<String, List<String>> bicEntries = new TreeMap<>();

        for (Path jar : jars) {
            String jarName = jar.getFileName().toString();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                List<String> ids = new ArrayList<>();
                List<String> nss = new ArrayList<>();
                List<String> ores = new ArrayList<>();
                List<String> bic = new ArrayList<>();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String n = e.getName();
                    if (e.isDirectory()) continue;
                    if (n.equals("META-INF/neoforge.mods.toml") || n.equals("META-INF/mods.toml")) {
                        Matcher m = MOD_ID.matcher(readAll(zip, e));
                        while (m.find()) ids.add(m.group(1));
                    }
                    Matcher nsm = NS_PATH.matcher(n);
                    if (nsm.find()) {
                        String ns = nsm.group(1);
                        if (!nss.contains(ns)) nss.add(ns);
                    }
                    // block tag files: data/<ns>/tags/block/**.json
                    if (n.startsWith("data/") && n.contains("/tags/block/") && n.endsWith(".json")) {
                        String tagNs = n.substring(5, n.indexOf('/', 5));
                        String tagPath = n.substring(n.indexOf("/tags/block/") + 12, n.length() - 5);
                        String tagId = tagNs + ":" + tagPath;
                        String content = readAll(zip, e);
                        Map<String, List<String>> tag = blockTags.computeIfAbsent(tagId, k -> new TreeMap<>());
                        Matcher vm = TAG_VALUE.matcher(valuesSection(content));
                        while (vm.find()) {
                            tag.computeIfAbsent(vm.group(1), k -> new ArrayList<>()).add(jarName);
                        }
                    }
                    // ore-like block candidates
                    if (n.startsWith("assets/") && n.contains("/blockstates/") && n.endsWith(".json")) {
                        String ns = n.substring(7, n.indexOf('/', 7));
                        String block = n.substring(n.lastIndexOf('/') + 1, n.length() - 5);
                        if (ORE_LIKE.matcher(block).find()) ores.add(ns + ":" + block);
                    }
                    if (n.startsWith("data/") && (n.contains("/loot_table/") || n.contains("/loot_tables/")) && n.endsWith(".json")) {
                        String ns = n.substring(5, n.indexOf('/', 5));
                        String name = n.substring(n.lastIndexOf('/') + 1, n.length() - 5);
                        if (ORE_LIKE.matcher(name).find()) ores.add("loot:" + ns + ":" + n.substring(n.indexOf('/', 5) + 1));
                    }
                    // recipe result ids
                    if (n.startsWith("data/") && n.contains("/recipe") && n.endsWith(".json")) {
                        String ns = n.substring(5, n.indexOf('/', 5));
                        String content = readAll(zip, e);
                        for (String id : extractResultIds(content)) {
                            recipeResults.computeIfAbsent(ns, k -> new ArrayList<>());
                            if (!recipeResults.get(ns).contains(id)) recipeResults.get(ns).add(id);
                        }
                    }
                    // item models as item proxy
                    if (n.startsWith("assets/") && n.contains("/models/item/") && n.endsWith(".json")) {
                        String ns = n.substring(7, n.indexOf('/', 7));
                        String item = n.substring(n.indexOf("/models/item/") + 13, n.length() - 5);
                        itemModels.computeIfAbsent(ns, k -> new ArrayList<>());
                        if (!itemModels.get(ns).contains(item)) itemModels.get(ns).add(item);
                    }
                    // born in chaos entry inventory
                    if (n.contains("borninchaos")) bic.add(n);
                }
                modIds.put(jarName, ids);
                namespaces.put(jarName, nss);
                oreCandidates.put(jarName, ores);
                bicEntries.put(jarName, bic);
            }
        }

        writeJson(outDir.resolve("mods-index.json"), listMap(modIds));
        writeJson(outDir.resolve("namespace-index.json"), listMap(namespaces));
        writeJson(outDir.resolve("block-tags.json"), nestedMap(blockTags));
        writeJson(outDir.resolve("ore-candidates.json"), listMap(oreCandidates));
        writeJson(outDir.resolve("recipe-results.json"), listMap(recipeResults));
        writeJson(outDir.resolve("item-models.json"), listMap(itemModels));
        writeJson(outDir.resolve("bic-entries.json"), listMap(bicEntries));
        System.out.println("wrote reports to " + outDir);
    }

    private static String readAll(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String valuesSection(String json) {
        int i = json.indexOf("\"values\"");
        return i < 0 ? "" : json.substring(i);
    }

    private static List<String> extractResultIds(String recipeJson) {
        List<String> ids = new ArrayList<>();
        Matcher rm = RECIPE_RESULT.matcher(recipeJson);
        while (rm.find()) {
            int start = rm.end();
            String tail = recipeJson.substring(start, Math.min(start + 400, recipeJson.length()));
            Matcher m;
            if ((m = RECIPE_RESULT_ID.matcher(tail)).find() && m.start() < 40) ids.add(m.group(1));
            else if ((m = RECIPE_RESULT_ITEM.matcher(tail)).find() && m.start() < 40) ids.add(m.group(1));
            else if ((m = RECIPE_RESULT_COUNT.matcher(tail)).find() && m.start() < 40) ids.add(m.group(1));
        }
        return ids;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String listMap(Map<String, List<String>> map) {
        StringBuilder sb = new StringBuilder("{\n");
        map.forEach((k, v) -> {
            sb.append("  \"").append(esc(k)).append("\": [");
            for (int i = 0; i < v.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(esc(v.get(i))).append("\"");
            }
            sb.append("],\n");
        });
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.append("\n}\n").toString();
    }

    private static String nestedMap(Map<String, Map<String, List<String>>> map) {
        StringBuilder sb = new StringBuilder("{\n");
        map.forEach((tag, values) -> {
            sb.append("  \"").append(esc(tag)).append("\": {\n");
            values.forEach((v, jars) -> {
                sb.append("    \"").append(esc(v)).append("\": [");
                for (int i = 0; i < jars.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(esc(jars.get(i))).append("\"");
                }
                sb.append("],\n");
            });
            if (sb.charAt(sb.length() - 2) == ',') sb.setLength(sb.length() - 2);
            sb.append("\n  },\n");
        });
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.append("\n}\n").toString();
    }

    private static void writeJson(Path path, String json) throws IOException {
        Files.writeString(path, json);
        System.out.println("  " + path.getFileName() + " (" + json.length() + " bytes)");
    }
}
