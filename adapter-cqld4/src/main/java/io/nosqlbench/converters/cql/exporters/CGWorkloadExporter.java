/*
 * Copyright (c) 2022 nosqlbench
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.nosqlbench.converters.cql.exporters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.nosqlbench.converters.cql.cqlast.*;
import io.nosqlbench.converters.cql.exporters.binders.*;
import io.nosqlbench.converters.cql.exporters.transformers.CGTransformersInit;
import io.nosqlbench.converters.cql.parser.CqlModelParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.NonPrintableStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.representer.BaseRepresenter;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The unit of generation is simply everything that is provided to the exporter together.
 * Thus if you feed it one create statement at a time, it will yield a workload with just that,
 * and if you feed it an elaborate schema, it will generate a workload inclusive of all
 * provided elements.
 *
 * @see <a href="https://cassandra.apache.org/doc/trunk/cassandra/cql/index.html">Apache Cassandra CQL Docs</a>
 */
public class CGWorkloadExporter {
    private final static Logger logger = LogManager.getLogger(CGWorkloadExporter.class);

    private final BindingsLibrary defaultBindings = new CGDefaultCqlBindings();

    private NamingFolio namer;
    private BindingsAccumulator bindings = new BindingsAccumulator(namer, List.of(defaultBindings));
    private final CqlModel model;
    private final Map<String, String> bindingsMap = new LinkedHashMap<>();
    private final int DEFAULT_RESOLUTION = 10000;
    String replication;
    String namingTemplate;
    private List<String> includedKeyspaces;
    private double partitionMultiplier;
    private final Map<String, Double> timeouts = new HashMap<String, Double>(Map.of(
        "create", 60.0,
        "truncate", 900.0,
        "drop", 900.0,
        "scan", 30.0,
        "select", 10.0,
        "insert", 10.0,
        "delete", 10.0,
        "update", 10.0
    ));
    private boolean elideUnusedTables;
    private Map<String, List<String>> blockplan = Map.of();

    public CGWorkloadExporter(CqlModel model, CGTransformersInit transformers) {
        this.model = model;
        for (Function<CqlModel, CqlModel> transformer : transformers.get()) {
            CqlModel modified = transformer.apply(this.model);
            model = modified;
        }
    }

    public CGWorkloadExporter(String ddl, Path srcpath, CGTransformersInit transformers) {
        this(CqlModelParser.parse(ddl, srcpath), transformers);
    }

    public CGWorkloadExporter(String ddl, CGTransformersInit transformers) {
        this(ddl, null, transformers);
    }

    public CGWorkloadExporter(Path path, CGTransformersInit transformers) {
        this(CqlModelParser.parse(path), transformers);
    }

    public static void main(String[] args) {
        logger.info("running CQL workload exporter with args:" + Arrays.toString(args));

        if (args.length == 0) {
            throw new RuntimeException("Usage example: PROG filepath.cql filepath.yaml");
        }
        Path srcpath = Path.of(args[0]);
        if (!srcpath.toString().endsWith(".cql")) {
            throw new RuntimeException("File '" + srcpath + "' must end in .cql");
        }
        if (!Files.exists(srcpath)) {
            throw new RuntimeException("File '" + srcpath + "' does not exist.");
        }

        Path target = null;
        if (args.length >= 2) {
            target = Path.of(args[1]);
            logger.info("using output path as '" + target + "'");
        } else {
            target = Path.of(srcpath.toString().replace(".cql", ".yaml"));
            logger.info("assumed output path as '" + target + "'");

        }

        if (!target.toString().endsWith(".yaml")) {
            throw new RuntimeException("Target file must end in .yaml, but it is '" + target + "'");
        }
        if (Files.exists(target) && !target.toString().startsWith("_")) {
            throw new RuntimeException("Target file '" + target + "' exists. Please remove it first or use a different target file name.");
        }

        Yaml yaml = new Yaml();
        Path cfgpath = Path.of("exporter.yaml");

        CGWorkloadExporter exporter;
        if (Files.exists(cfgpath)) {
            try {
                CGTransformersInit transformers = new CGTransformersInit();
                String configfile = Files.readString(cfgpath);
                Map cfgmap = yaml.loadAs(configfile, Map.class);
                if (cfgmap.containsKey("model_transformers")) {
                    transformers.accept((List<Map<String, ?>>) cfgmap.get("model_transformers"));
                }

                exporter = new CGWorkloadExporter(srcpath, transformers);

                String defaultNamingTemplate = cfgmap.get("naming_template").toString();
                exporter.setNamingTemplate(defaultNamingTemplate);

                String partition_multipler = cfgmap.get("partition_multiplier").toString();
                exporter.setPartitionMultiplier(Double.parseDouble(partition_multipler));

                exporter.configureTimeouts(cfgmap.get("timeouts"));
                exporter.configureElideUnusedTables(cfgmap.get("elide_unused_tables"));

                exporter.configureBlocks(cfgmap.get("blockplan"));

                String workload = exporter.getWorkloadAsYaml();
                try {
                    Files.writeString(
                        target,
                        workload,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                    );
                } catch (IOException e) {
                    e.printStackTrace();
                }


            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Map<String, Object> generateBlocks() {
        namer.informNamerOfAllKnownNames(model);

        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("description", "Auto-generated workload from source schema.");
        workload.put("scenarios", genScenarios(model));
        workload.put("bindings", bindingsMap);
        Map<String, Object> blocks = new LinkedHashMap<>();
        workload.put("blocks", blocks);

        for (Map.Entry<String, List<String>> blocknameAndComponents : blockplan.entrySet()) {
            String blockname = blocknameAndComponents.getKey();
            List<String> components = blocknameAndComponents.getValue();

            LinkedHashMap<String, Object> block = new LinkedHashMap<>(
                Map.of("params", new LinkedHashMap<String, Object>())
            );
            for (String component : components) {
                Map<String, Object> additions = switch (component) {
                    case "schema-keyspaces" -> genCreateKeyspacesOpTemplates(model, blockname);
                    case "schema-tables" -> genCreateTablesOpTemplates(model, blockname);
                    case "schema-types" -> genCreateTypesOpTemplates(model, blockname);
                    case "drop-types" -> genDropTypesBlock(model, blockname);
                    case "drop-tables" -> genDropTablesBlock(model, blockname);
                    case "drop-keyspaces" -> genDropKeyspacesOpTemplates(model, blockname);
                    case "truncate-tables" -> genTruncateTablesOpTemplates(model, blockname);
                    case "insert" -> genInsertOpTemplates(model, blockname);
                    case "select" -> genSelectOpTemplates(model, blockname);
                    case "scan-10" -> genScanOpTemplates(model, blockname);
                    case "update" -> genUpdateOpTemplates(model, blockname);
                    default -> throw new RuntimeException("Unable to create block entries for " + component + ".");
                };
                block.putAll(additions);
            }
            simplifyTimeouts(block);
            blocks.put(blockname, block);
        }
        bindingsMap.putAll(bindings.getAccumulatedBindings());
        return workload;
    }

    private void simplifyTimeouts(Map<String, Object> block) {
        Map<Double, List<String>> byTimeout = new LinkedHashMap<>();
        Map<String, Object> ops = (Map<String, Object>) block.get("ops");
        ops.forEach((opname, opmap) -> {
            double timeout = (double) (((Map<String, Object>) opmap).get("timeout"));
            byTimeout.computeIfAbsent(timeout, d -> new ArrayList<>()).add(opname);
        });
        List<Double> timeouts = byTimeout.keySet().stream().sorted(Double::compare).toList();
        if (timeouts.size() == 1) {
            ((Map<String, Object>) block.computeIfAbsent("params", p -> new LinkedHashMap<>())).put("timeout", timeouts.get(0));
            Set<String> opnames = ((Map<String, Object>) block.get("ops")).keySet();
            for (String opname : opnames) {

                Map<String, Object> opmap = (Map<String, Object>) ops.get(opname);
                Map<String, Object> newOp = new LinkedHashMap<>(opmap);
                newOp.remove("timeout");
                ops.put(opname, newOp);
            }
        }
    }

    private void configureBlocks(Object generate_blocks_spec) {
        if (generate_blocks_spec == null) {
            throw new RuntimeException("Error with generate blocks, required parameter 'blockplan' is missing");
        }
        if (generate_blocks_spec instanceof Map blocksmap) {
            Map<String, List<String>> planmap = new LinkedHashMap<>();
            for (Map.Entry<String, String> blockplan : ((Map<String, String>) blocksmap).entrySet()) {
                planmap.put(blockplan.getKey(), Arrays.stream(blockplan.getValue().split(", ")).toList());
            }
            this.blockplan = planmap;
        } else {
            throw new RuntimeException("Unrecognized type '" + generate_blocks_spec.getClass().getSimpleName() + "' for 'blockplan' config.");
        }
    }

    private void configureElideUnusedTables(Object elide_unused_tables) {
        if (elide_unused_tables == null) {
            this.elideUnusedTables = false;
        } else {
            this.elideUnusedTables = Boolean.parseBoolean(elide_unused_tables.toString());
        }
    }

    public void configureTimeouts(Object spec) {
        if (spec instanceof Map specmap) {
            for (Object key : specmap.keySet()) {
                if (this.timeouts.containsKey(key.toString())) {
                    Object value = specmap.get(key.toString());
                    if (value instanceof Number number) {
                        this.timeouts.put(key.toString(), number.doubleValue());
                        logger.info("configured '" + key + "' timeout as " + this.timeouts.get(key.toString()) + "S");
                    }

                } else {
                    throw new RuntimeException("timeout type '" + key + "' unknown. Known types: " + this.timeouts.keySet());
                }

            }
        }

    }

    private void setPartitionMultiplier(double multipler) {
        this.partitionMultiplier = multipler;
    }

    public void setNamingTemplate(String namingTemplate) {
        this.namingTemplate = namingTemplate;
        this.namer = new NamingFolio(namingTemplate);
        this.bindings = new BindingsAccumulator(namer, List.of(defaultBindings));
    }

    private LinkedHashMap<String, Object> genScenarios(CqlModel model) {
        return new LinkedHashMap<>() {{
            put("default",
                new LinkedHashMap<>() {{
                    put("schema", "run driver=cql tags=block:'schema-.*' threads===UNDEF cycles===UNDEF");
                    put("rampup", "run driver=cql tags=block:'rampup-.*' threads=auto cycles===TEMPLATE(rampup-cycles,10000)");
                    put("main", "run driver=cql tags=block:'main-.*' threads=auto cycles===TEMPLATE(main-cycles,10000)");

                }});
            put("truncate", "run driver=cql tags=block:'truncate-.*' threads===UNDEF cycles===UNDEF");
            put("schema-keyspaces", "run driver=cql tags=block:'schema-keyspaces' threads===UNDEF cycles===UNDEF");
            put("schema-types", "run driver=cql tags=block:'schema-types' threads===UNDEF cycles===UNDEF");
            put("schema-tables", "run driver=cql tags=block:'schema-tables' threads===UNDEF cycles===UNDEF");
            put("drop", "run driver=cql tags=block:'drop-.*' threads===UNDEF cycles===UNDEF");
            put("drop-tables", "run driver=cql tags=block:'drop-tables' threads===UNDEF cycles===UNDEF");
            put("drop-types", "run driver=cql tags=block:'drop-types' threads===UNDEF cycles===UNDEF");
            put("drop-keyspaces", "run driver=cql tags=block:'drop-keyspaces' threads===UNDEF cycles===UNDEF");

        }};
    }

    private Map<String, Object> genScanOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> blockdata = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        blockdata.put("ops", ops);
        for (CqlTable table : model.getTableDefs()) {
            ops.put(
                namer.nameFor(table, "optype", "scan", "blockname", blockname),
                Map.of(
                    "prepared", genScanSyntax(table),
                    "timeout", timeouts.get("scan"),
                    "ratio", readRatioFor(table)
                )
            );
        }
        return blockdata;
    }

    private String genScanSyntax(CqlTable table) {
        return """
            select * from  KEYSPACE.TABLE
            PREDICATE
            LIMIT;
            """
            .replace("KEYSPACE", table.getKeySpace())
            .replace("TABLE", table.getName())
            .replace("PREDICATE", genPredicateTemplate(table, -1))
            .replace("LIMIT", genLimitSyntax(table));
    }


    private Map<String, Object> genSelectOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> blockdata = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        blockdata.put("ops", ops);
        for (CqlTable table : model.getTableDefs()) {
            ops.put(
                namer.nameFor(table, "optype", "select", "blockname", blockname),
                Map.of(
                    "prepared", genSelectSyntax(table),
                    "timeout", timeouts.get("select"),
                    "ratio", readRatioFor(table)
                )
            );
        }
        return blockdata;
    }

    private String genSelectSyntax(CqlTable table) {
        return """
            select * from  KEYSPACE.TABLE
            PREDICATE
            LIMIT;
            """
            .replace("KEYSPACE", table.getKeySpace())
            .replace("TABLE", table.getName())
            .replace("PREDICATE", genPredicateTemplate(table, 0))
            .replace("LIMIT", genLimitSyntax(table));
    }

    private String genLimitSyntax(CqlTable table) {
        return " LIMIT 10";
    }

    private Map<String, Object> genInsertOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> blockdata = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        blockdata.put("ops", ops);
        for (CqlTable table : model.getTableDefs()) {
            ops.put(
                namer.nameFor(table, "optype", "insert", "blockname", blockname),
                Map.of(
                    "prepared", genInsertSyntax(table),
                    "timeout", timeouts.get("insert"),
                    "ratio", writeRatioFor(table)
                )
            );
        }
        return blockdata;
    }

    private String genInsertSyntax(CqlTable table) {
        if (isCounterTable(table)) {
            logger.warn("skipping insert on counter table '" + table.getFullName());
        }

        return """
            insert into KEYSPACE.TABLE
            ( FIELDNAMES )
            VALUES
            ( BINDINGS );
            """
            .replace("KEYSPACE", table.getKeySpace())
            .replace("TABLE", table.getName())
            .replace("FIELDNAMES",
                String.join(", ",
                    table.getColumnDefinitions().stream()
                        .map(CqlColumnDef::getName).toList()))
            .replaceAll("BINDINGS",
                String.join(", ",
                    table.getColumnDefinitions().stream()
                        .map(cdef -> {
                            if (cdef.isLastPartitionKey()) {
                                return dividedBinding(cdef, table);
                            } else {
                                return bindings.forColumn(cdef);
                            }
                        })
                        .map(c -> "{" + c.getName() + "}").toList()));
    }

    private Binding dividedBinding(CqlColumnDef columnDef, CqlTable tableDef) {
        CGTableStats stats = tableDef.getTableAttributes();
        if (stats==null) {
            return bindings.forColumn(columnDef);
        }
        String partitionsSpec = stats.getAttribute("Number of partitions (estimate)");
        if (partitionsSpec==null) {
        }
        double estimatedPartitions = Double.parseDouble(partitionsSpec);
        long modulo = (long)(estimatedPartitions*=partitionMultiplier);
        if (modulo==0) {
            return bindings.forColumn(columnDef);
        }
        modulo = quantizeModuloByMagnitude(modulo,1);
        logger.info("Set partition modulo for " + tableDef.getFullName() + " to " + modulo);
        Binding binding = bindings.forColumn(columnDef, "Mod(" + modulo + "L); ");
        return binding;
    }

    public static long quantizeModuloByMagnitude(long modulo, int significand) {
        double initial = modulo;
        double log10 = Math.log10(initial);
        int zeroes = (int)log10;
        zeroes = Math.max(1,zeroes-(significand-1));
        long fractional = (long) Math.pow(10,zeroes);
        long partial = ((long)initial/fractional) * fractional;
        long nextPartial = partial+fractional;
        if (Math.abs(initial-partial)<=Math.abs(initial-nextPartial)) {
            return partial;
        } else {
            return nextPartial;
        }
    }

    private Map<String, Object> genUpdateOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> blockdata = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        blockdata.put("ops", ops);
        for (CqlTable table : model.getTableDefs()) {
            ops.put(
                namer.nameFor(table, "optype", "update", "blockname", blockname),
                Map.of(
                    "prepared", genUpdateSyntax(table),
                    "timeout", timeouts.get("update"),
                    "ratio", writeRatioFor(table)
                )
            );
        }
        return blockdata;
    }


    private boolean isCounterTable(CqlTable table) {
        return table.getColumnDefinitions().stream()
            .anyMatch(cd -> cd.getTrimmedTypedef().equalsIgnoreCase("counter"));
    }

    private int totalRatioFor(CqlTable table) {
        if (table.getTableAttributes() == null || table.getTableAttributes().size() == 0) {
            return 1;
        }
        return readRatioFor(table) + writeRatioFor(table);

    }

    private int readRatioFor(CqlTable table) {
        if (table.getTableAttributes() == null || table.getTableAttributes().size() == 0) {
            return 1;
        }
        double weighted_reads = Double.parseDouble(table.getTableAttributes().getAttribute("weighted_reads"));
        return (int) (weighted_reads * DEFAULT_RESOLUTION);
    }

    private int writeRatioFor(CqlTable table) {
        if (table.getTableAttributes() == null || table.getTableAttributes().size() == 0) {
            return 1;
        }
        double weighted_writes = Double.parseDouble(table.getTableAttributes().getAttribute("weighted_writes"));
        return (int) (weighted_writes * DEFAULT_RESOLUTION);
    }

    /**
     * If keycount is 0, all key fields including partition and clustering fields
     * are qualfied with predicates.
     * If keycount is positive, then only that many will be included.
     * If keycount is negative, then that many keyfields will be removed from the
     * predicate starting with the rightmost (innermost) fields first.
     *
     * @param table
     * @param keycount
     * @return
     */
    private String genPredicateTemplate(CqlTable table, int keycount) {

        StringBuilder sb = new StringBuilder();
        LinkedList<CqlColumnDef> pkeys = new LinkedList<>();
        for (String pkey : table.getPartitionKeys()) {
            CqlColumnDef coldef = table.getColumnDefForName(pkey);
            pkeys.push(coldef);
        }
        for (String ccol : table.getClusteringColumns()) {
            CqlColumnDef coldef = table.getColumnDefForName(ccol);
            pkeys.push(coldef);
        }

        if (keycount > 0) {
            while (pkeys.size() > keycount) {
                pkeys.pop();
            }
        } else if (keycount < 0) {
            for (int i = 0; i > keycount; i--) {
                pkeys.pop();
            }
        }
        var lastcount = keycount;
        keycount = Math.max(table.getPartitionKeys().size(), keycount);
        if (keycount != lastcount) {
            logger.debug("minimum keycount for " + table.getFullName() + " adjusted from " + lastcount + " to " + keycount);
        }

        // TODO; constraints on predicates based on valid constructions
        pkeys.stream().map(this::genPredicatePart)
            .forEach(p -> {
                sb.append(p).append("\n  AND ");
            });
        if (sb.length() > 0) {
            sb.setLength(sb.length() - "\n  AND ".length());
        }
        return sb.toString();
    }

    private String genPredicatePart(CqlColumnDef def) {
        String typeName = def.getTrimmedTypedef();
        Binding binding = bindings.forColumn(def);

        return def.getName() + "={" + binding.getName() + "}";
    }

    private String genUpdateSyntax(CqlTable table) {
        return """
            update KEYSPACE.TABLE
            set ASSIGNMENTS
            where PREDICATES;
            """
            .replaceAll("KEYSPACE", table.getKeySpace())
            .replaceAll("TABLE", table.getName())
            .replaceAll("PREDICATES", genPredicateTemplate(table, 0))
            .replaceAll("ASSIGNMENTS", genAssignments(table));
    }

    private String genAssignments(CqlTable table) {
        StringBuilder sb = new StringBuilder();
        for (CqlColumnDef coldef : table.getNonKeyColumnDefinitions()) {
            if (coldef.isCounter()) {
                sb.append(coldef.getName()).append("=")
                    .append(coldef.getName()).append("+").append("{").append(bindings.forColumn(coldef).getName()).append("}")
                    .append(", ");
            } else {
                sb.append(coldef.getName()).append("=")
                    .append("{").append(bindings.forColumn(coldef).getName()).append("}")
                    .append(", ");
            }
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - ", ".length());
        }
        return sb.toString();
    }


    public String getWorkloadAsYaml() {
        DumpSettings dumpSettings = DumpSettings.builder()
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setIndent(2)
            .setDefaultScalarStyle(ScalarStyle.PLAIN)
            .setMaxSimpleKeyLength(1000)
            .setWidth(100)
            .setSplitLines(true)
            .setIndentWithIndicator(true)
            .setMultiLineFlow(true)
            .setNonPrintableStyle(NonPrintableStyle.ESCAPE)
            .build();
        BaseRepresenter r;
        Dump dump = new Dump(dumpSettings);

        Map<String, Object> workload = generateBlocks();
        return dump.dumpToString(workload);
    }

    public String getModelAsJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(model);
    }

    public String getWorkoadAsJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> workload = generateBlocks();
        return gson.toJson(workload);
    }


    private Map<String, Object> genDropTablesBlock(CqlModel model, String blockname) {
        Map<String, Object> dropTablesBlock = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        dropTablesBlock.put("ops", ops);
        for (CqlTable table : model.getTableDefs()) {
            ops.put(
                namer.nameFor(table, "optype", "drop", "blockname", blockname),
                Map.of(
                    "simple", "drop table " + table.getFullName() + ";",
                    "timeout", timeouts.get("drop")
                )
            );
        }
        return dropTablesBlock;
    }

    private Map<String, Object> genDropTypesBlock(CqlModel model, String blockname) {
        Map<String, Object> dropTypesBlock = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        dropTypesBlock.put("ops", ops);
        for (CqlType type : model.getTypes()) {
            ops.put(
                namer.nameFor(type, "optype", "drop-type", "blockname", blockname),
                Map.of(
                    "simple", "drop type " + type.getKeyspace() + "." + type.getName() + ";",
                    "timeout", timeouts.get("drop")
                )
            );
        }
        return dropTypesBlock;
    }

    private Map<String, Object> genDropKeyspacesOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> dropTypesBlock = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        dropTypesBlock.put("ops", ops);
        for (CqlType type : model.getTypes()) {
            ops.put(
                namer.nameFor(type, "optype", "drop-keyspace", "blockname", blockname),
                Map.of(
                    "simple", "drop keyspace " + type.getKeyspace() + ";",
                    "timeout", timeouts.get("drop")
                )
            );
        }
        return dropTypesBlock;
    }


    private Map<String, Object> genTruncateTablesOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> truncateblock = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        truncateblock.put("ops", ops);

        for (CqlTable table : model.getTableDefs()) {
            ops.put(
                namer.nameFor(table, "optype", "truncate", "blockname", blockname),
                Map.of(
                    "simple", "truncate " + table.getFullName() + ";",
                    "timeout", timeouts.get("truncate")
                )

            );
        }
        return truncateblock;
    }

    private Map<String, Object> genCreateKeyspacesOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> schemablock = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();

        for (CqlKeyspace ks : model.getKeyspacesByName().values()) {
            ops.put(
                namer.nameFor(ks, "optype", "create", "blockname", blockname),
                Map.of(
                    "simple", genKeyspaceDDL(ks),
                    "timeout", timeouts.get("create")
                )
            );
        }

        schemablock.put("ops", ops);
        return schemablock;
    }

    private Map<String, Object> genCreateTypesOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> blockdata = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();
        blockdata.put("ops", ops);

        for (String keyspace : model.getTypesByKeyspaceAndName().keySet()) {
            for (CqlType type : model.getTypesByKeyspaceAndName().get(keyspace).values()) {
                ops.put(
                    namer.nameFor(type, "optype", "create", "blockname", blockname),
                    Map.of(
                        "simple", genTypeDDL(type),
                        "timeout", timeouts.get("create")
                    )
                );
            }
        }
        return blockdata;

    }

    private String genKeyspaceDDL(CqlKeyspace keyspace) {
        return """
            create keyspace KEYSPACE
            with replication = {REPLICATION}DURABLEWRITES?;
            """
            .replace("KEYSPACE", keyspace.getName())
            .replace("REPLICATION", keyspace.getReplicationData())
            .replace("DURABLEWRITES?", keyspace.isDurableWrites() ? "" : "\n and durable writes = false")
            ;
    }

    private Map<String, Object> genCreateTablesOpTemplates(CqlModel model, String blockname) {
        Map<String, Object> schemablock = new LinkedHashMap<>();
        Map<String, Object> ops = new LinkedHashMap<>();

        for (String ksname : model.getTablesByNameByKeyspace().keySet()) {
            for (CqlTable cqltable : model.getTablesByNameByKeyspace().get(ksname).values()) {
                if (elideUnusedTables && totalRatioFor(cqltable) == 0.0d) {
                    logger.info("eliding table " + ksname + "." + cqltable.getName() + " since its total op ratio was " + totalRatioFor(cqltable));
                    continue;
                }
                ops.put(
                    namer.nameFor(cqltable, "optype", "create", "blockname", blockname),
                    Map.of(
                        "simple", genTableDDL(cqltable),
                        "timeout", timeouts.get("create")
                    )
                );
            }
        }

        schemablock.put("ops", ops);
        return schemablock;
    }


    private String genTypeDDL(CqlType type) {
        return """
            create type KEYSPACE.TYPENAME (
            TYPEDEF
            );
            """
            .replace("KEYSPACE", type.getKeyspace())
            .replace("TYPENAME", type.getName())
            .replace("TYPEDEF", type.getFields().entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(",\n")));
    }

    private Object genTableDDL(CqlTable cqltable) {
        if (cqltable.isCompactStorage()) {
            logger.warn("COMPACT STORAGE is not supported, eliding this option for table '" + cqltable.getFullName() + "'");
        }

        return """
            create table if not exists KEYSPACE.TABLE (
            COLUMN_DEFS,
            primary key (PRIMARYKEY)
            )CLUSTERING;
            """
            .replace("KEYSPACE", cqltable.getKeySpace())
            .replace("TABLE", cqltable.getName())
            .replace("COLUMN_DEFS", genTableColumnDDL(cqltable))
            .replace("PRIMARYKEY", genPrimaryKeyDDL(cqltable))
            .replace("CLUSTERING", genTableClusteringOrderDDL(cqltable));

    }

    private String genPrimaryKeyDDL(CqlTable cqltable) {
        StringBuilder sb = new StringBuilder("(");
        for (String partitionKey : cqltable.getPartitionKeys()) {
            sb.append(partitionKey).append(", ");
        }
        sb.setLength(sb.length() - ", ".length());
        sb.append(")");
        for (String clusteringColumn : cqltable.getClusteringColumns()) {
            sb.append(", ").append(clusteringColumn);
        }
        return sb.toString();
    }

    private String genTableClusteringOrderDDL(CqlTable cqltable) {
        if (cqltable.getClusteringOrders().size() == 0) {
            return "";
        } else {
            StringBuilder sb = new StringBuilder(" with clustering order by (\n");
            for (int i = 0; i < cqltable.getClusteringOrders().size(); i++) {
                sb.append(cqltable.getClusteringColumns().get(i));
                sb.append(" ");
                sb.append(cqltable.getClusteringOrders().get(i));
                sb.append(",\n");
            }
            if (sb.length() > 0) {
                sb.setLength(sb.length() - ",\n".length());
            }
            sb.append(")");
            return sb.toString();
        }
    }

    private String genTableColumnDDL(CqlTable cqltable) {
        return cqltable.getColumnDefinitions().stream()
            .map(cd -> cd.getName() + " " + cd.getTrimmedTypedef())
            .collect(Collectors.joining(",\n"));
    }


}
