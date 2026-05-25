package tap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import com.hypixel.hytale.plugin.early.ClassTransformer;

public final class TapTransformer implements ClassTransformer {

    private static final String PACKET_DESC      = "Lcom/hypixel/hytale/protocol/Packet;";
    private static final String BYTEBUF_DESC     = "Lio/netty/buffer/ByteBuf;";
    private static final String PACKETINFO_DESC  = "Lcom/hypixel/hytale/protocol/PacketRegistry$PacketInfo;";
    private static final String TAP_OWNER        = "com/hypixel/hytale/plugin/early/PacketTap";

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public byte[] transform(String name, String internalName, byte[] classBytes) {
        if (!"com/hypixel/hytale/protocol/io/PacketIO".equals(internalName)) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassWriter cw = new ClassWriter(cr, 0);
            final int[] sendCount = {0};
            final int[] recvCount = {0};
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String mName, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, mName, desc, sig, ex);
                    if ((access & Opcodes.ACC_STATIC) == 0) return mv;

                    final int packetSlot = findSlot(desc, PACKET_DESC);
                    final int inBufSlot  = findSlot(desc, BYTEBUF_DESC);
                    final int infoSlot   = findSlot(desc, PACKETINFO_DESC);
                    final int firstIntSlot = findFirstIntSlot(desc);

                    final boolean isSend =
                        ("writeFramedPacket".equals(mName) || "writeFramedPacketWithInfo".equals(mName))
                        && packetSlot >= 0
                        && inBufSlot >= 0;

                    final boolean isRecv =
                        "readFramedPacketWithInfo".equals(mName)
                        && inBufSlot >= 0
                        && infoSlot >= 0
                        && firstIntSlot >= 0;

                    if (!isSend && !isRecv) return mv;

                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (isSend) {
                                mv.visitVarInsn(Opcodes.ALOAD, packetSlot);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAP_OWNER,
                                        "onSend", "(Ljava/lang/Object;)V", false);
                                sendCount[0]++;
                            } else {
                                mv.visitVarInsn(Opcodes.ALOAD, infoSlot);
                                mv.visitVarInsn(Opcodes.ALOAD, inBufSlot);
                                mv.visitVarInsn(Opcodes.ILOAD, firstIntSlot);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAP_OWNER,
                                        "onRecv", "(Ljava/lang/Object;Ljava/lang/Object;I)V", false);
                                recvCount[0]++;
                            }
                        }
                    };
                }
            };
            cr.accept(cv, 0);
            System.out.println("[PacketTap] instrumented PacketIO (send=" + sendCount[0] + ", recv=" + recvCount[0] + ")");
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[PacketTap] transform failed: " + t);
            return null;
        }
    }

    private static int findSlot(String desc, String typeDesc) {
        Type[] args = Type.getArgumentTypes(desc);
        int slot = 0;
        for (Type t : args) {
            if (t.getDescriptor().equals(typeDesc)) return slot;
            slot += t.getSize();
        }
        return -1;
    }

    private static int findFirstIntSlot(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        int slot = 0;
        for (Type t : args) {
            if (t.getSort() == Type.INT) return slot;
            slot += t.getSize();
        }
        return -1;
    }
}
