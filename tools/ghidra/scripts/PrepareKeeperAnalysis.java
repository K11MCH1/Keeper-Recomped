// Restore LE object permissions in programs imported before the loader fix.
// @category KeeperRecomped
import ghidra.app.script.GhidraScript;
import ghidra.program.model.mem.MemoryBlock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

public class PrepareKeeperAnalysis extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("PrepareKeeperAnalysis original-EXE");
        byte[] image = Files.readAllBytes(Paths.get(args[0]));
        ByteBuffer data = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        if (image.length < 64 || data.getShort(0) != (short)0x5a4d)
            throw new IllegalArgumentException("Expected an MZ/LE executable");
        int le = data.getInt(0x3c);
        if (le < 64 || le > image.length - 0x84 || data.getInt(le) != 0x454c)
            throw new IllegalArgumentException("Expected the supplied DOS4GW LE format");
        int count = data.getInt(le + 0x44);
        long table = Integer.toUnsignedLong(data.getInt(le + 0x40)) + le;
        if (count < 1 || count > 32 || table + count * 24L > image.length)
            throw new IllegalArgumentException("Invalid LE object table");
        for (int i = 0; i < count; ++i) {
            int entry = (int)table + i * 24;
            long size = Integer.toUnsignedLong(data.getInt(entry));
            long base = Integer.toUnsignedLong(data.getInt(entry + 4));
            int flags = data.getInt(entry + 8);
            MemoryBlock block = currentProgram.getMemory().getBlock(".object" + (i + 1));
            if (block == null || block.getStart().getOffset() != base || block.getSize() != size)
                throw new IllegalArgumentException("Object mapping differs from the original executable");
            block.setRead((flags & 1) != 0);
            block.setWrite((flags & 2) != 0);
            block.setExecute((flags & 4) != 0);
            println("LE permissions " + block.getName() + " flags=0x" + Integer.toHexString(flags));
        }
    }
}
