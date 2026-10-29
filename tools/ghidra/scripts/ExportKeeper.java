// Export actual original-binary decompilation as evidence, not recompilable source.
// @category KeeperRecomped
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ExportKeeper extends GhidraScript {
    private String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) throw new IllegalArgumentException("ExportKeeper output-directory [limit]");
        Path output = Paths.get(args[0]);
        Files.createDirectories(output.resolve("functions"));
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 30000;
        boolean refresh = args.length > 2 && args[2].equals("refresh");
        StringBuilder metadata = new StringBuilder("{\n  \"program\": " + q(currentProgram.getName()) + ",\n  \"sha256\": " + q(currentProgram.getExecutableSHA256()) + ",\n  \"language\": " + q(currentProgram.getLanguageID().toString()) + ",\n  \"compiler\": " + q(currentProgram.getCompilerSpec().getCompilerSpecID().toString()) + ",\n  \"blocks\": [");
        boolean first = true;
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!first) metadata.append(","); first = false;
            metadata.append("\n    {\"name\":" + q(block.getName()) + ",\"start\":" + q(block.getStart().toString()) + ",\"size\":" + block.getSize() + ",\"execute\":" + block.isExecute() + "}");
        }
        metadata.append("\n  ],\n  \"notice\": \"Ghidra pseudocode; unresolved types and ABI assumptions require manual reconstruction before ARM64 compilation.\"\n}\n");
        Files.writeString(output.resolve("program.json"), metadata.toString(), StandardCharsets.UTF_8);
        DecompInterface decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(true);
        if (!decompiler.openProgram(currentProgram)) throw new IOException(decompiler.getLastMessage());
        int done = 0, failed = 0, skipped = 0, outsideCode = 0;
        try (BufferedWriter index = Files.newBufferedWriter(output.resolve("functions.jsonl"), StandardCharsets.UTF_8)) {
            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext() && done + failed + skipped < limit && !monitor.isCancelled()) {
                Function fn = functions.next();
                MemoryBlock entryBlock = currentProgram.getMemory().getBlock(fn.getEntryPoint());
                boolean executable = entryBlock != null && entryBlock.isExecute();
                if (!executable) outsideCode++;
                String file = fn.getEntryPoint() + "_" + fn.getName().replaceAll("[^A-Za-z0-9_]", "_") + ".c";
                Path destination = output.resolve("functions").resolve(file);
                String state, diagnostic = "";
                if (!refresh && Files.isRegularFile(destination)) { skipped++; state = "cached"; }
                else {
                    DecompileResults result = decompiler.decompileFunction(fn, 8, monitor);
                    diagnostic = result.getErrorMessage();
                    if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        Files.writeString(destination, "/* Original x86 decompilation. Address: " + fn.getEntryPoint() + ". Not yet portable/recompiled. */\n" + result.getDecompiledFunction().getC(), StandardCharsets.UTF_8);
                        done++; state = "decompiled";
                    } else { failed++; state = "failed: " + result.getErrorMessage(); }
                }
                index.write("{\"address\":" + q(fn.getEntryPoint().toString()) + ",\"name\":" + q(fn.getName()) + ",\"size\":" + fn.getBody().getNumAddresses() + ",\"executable\":" + executable + ",\"file\":" + q("functions/" + file) + ",\"state\":" + q(state) + ",\"diagnostic\":" + q(diagnostic) + "}\n");
                if ((done + failed + skipped) % 100 == 0) {
                    index.flush(); println("EXPORT " + currentProgram.getName() + ": " + done + " new, " + skipped + " cached, " + failed + " failed");
                }
            }
        } finally { decompiler.dispose(); }
        Files.writeString(output.resolve("summary.json"), "{\"decompiled\":" + done + ",\"cached\":" + skipped + ",\"failed\":" + failed + ",\"functions_outside_executable\":" + outsideCode + ",\"total_functions\":" + currentProgram.getFunctionManager().getFunctionCount() + "}\n");
        println("EXPORT COMPLETE " + done + " new, " + skipped + " cached, " + failed + " failed");
    }
}
