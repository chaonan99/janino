
/*
 * Janino - An embedded Java[TM] compiler
 *
 * Copyright (c) 2001-2007, Arno Unkrig
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *    1. Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *    2. Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *    3. The name of the author may not be used to endorse or promote
 *       products derived from this software without specific prior
 *       written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.codehaus.janino;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.codehaus.janino.util.ClassFile;


/**
 * The context of the compilation of a function (constructor or method). Manages generation of
 * byte code, the exception table, generation of line number tables, allocation of local variables,
 * determining of stack size and local variable table size and flow analysis.
 */
public class CodeContext {
    private static final boolean DEBUG = false;

    private static final int INITIAL_SIZE   = 100;
    private static final int SIZE_INCREMENT = 128;

    private /*final*/ ClassFile classFile;

    private short           maxStack;
    private short           maxLocals;
    private byte[]          code;
    private /*final*/ Offset    beginning;
    private /*final*/ Inserter  end;
    private Inserter        currentInserter;
    private /*final*/ List      exceptionTableEntries; // ExceptionTableEntry

    /**
     * Create an empty "Code" attribute.
     */
    public CodeContext(ClassFile classFile) {
        this.classFile             = classFile;

        this.maxStack              = 0;
        this.maxLocals             = 0;
        this.code                  = new byte[CodeContext.INITIAL_SIZE];
        this.beginning             = new Offset();
        this.end                   = new Inserter();
        this.currentInserter       = this.end;
        this.exceptionTableEntries = new ArrayList();

        this.beginning.offset = 0;
        this.end.offset = 0;
        this.beginning.next = this.end;
        this.end.prev = this.beginning;
    }

    public ClassFile getClassFile() {
        return this.classFile;
    }

    /**
     * Allocate space for a local variable of the given size (1 or 2)
     * on the local variable array.
     *
     * As a side effect, the "max_locals" field of the "Code" attribute
     * is updated.
     *
     * The only way to deallocate local variables is to
     * {@link #saveLocalVariables()} and later {@link
     * #restoreLocalVariables()}.
     */
    public short allocateLocalVariable(
        short size // 1 or 2
    ) {
        short res = this.localVariableArrayLength;
        this.localVariableArrayLength += size;
        if (this.localVariableArrayLength > this.maxLocals) {
            this.maxLocals = this.localVariableArrayLength;
        }

        return res;
    }

    /**
     * Remember the current size of the local variables array.
     */
    public void saveLocalVariables() {
        this.savedLocalVariableArrayLengths.push(new Short(this.localVariableArrayLength));
    }

    /**
     * Restore the previous size of the local variables array.
     */
    public void restoreLocalVariables() {
        this.localVariableArrayLength = ((Short) this.savedLocalVariableArrayLengths.pop()).shortValue();
    }

    private static final byte UNEXAMINED = -1;
    private static final byte INVALID_OFFSET = -2;

    /**
     * 
     * @param dos
     * @param lineNumberTableAttributeNameIndex 0 == don't generate a "LineNumberTable" attribute
     * @throws IOException
     */
    protected void storeCodeAttributeBody(
        DataOutputStream dos,
        short            lineNumberTableAttributeNameIndex
    ) throws IOException {
        dos.writeShort(this.maxStack);                                // max_stack
        dos.writeShort(this.maxLocals);                               // max_locals
        dos.writeInt(this.end.offset);                                // code_length
        dos.write(this.code, 0, this.end.offset);                     // code
        dos.writeShort(this.exceptionTableEntries.size());            // exception_table_length
        for (int i = 0; i < this.exceptionTableEntries.size(); ++i) { // exception_table
            ExceptionTableEntry exceptionTableEntry = (ExceptionTableEntry) this.exceptionTableEntries.get(i);
            dos.writeShort(exceptionTableEntry.startPC.offset);
            dos.writeShort(exceptionTableEntry.endPC.offset);
            dos.writeShort(exceptionTableEntry.handlerPC.offset);
            dos.writeShort(exceptionTableEntry.catchType);
        }

        List attributes = new ArrayList(); // ClassFile.AttributeInfo

        // Add "LineNumberTable" attribute.
        if (lineNumberTableAttributeNameIndex != 0) {
            List lnt = new ArrayList();
            for (Offset o = this.beginning; o != null; o = o.next) {
                if (o instanceof LineNumberOffset) {
                    lnt.add(new ClassFile.LineNumberTableAttribute.Entry(o.offset, ((LineNumberOffset) o).lineNumber));
                }
            }
            ClassFile.LineNumberTableAttribute.Entry[] lnte = (ClassFile.LineNumberTableAttribute.Entry[]) lnt.toArray(new ClassFile.LineNumberTableAttribute.Entry[lnt.size()]);
            attributes.add(new ClassFile.LineNumberTableAttribute(
                lineNumberTableAttributeNameIndex, // attributeNameIndex
                lnte                               // lineNumberTableEntries
            ));
        }

        dos.writeShort(attributes.size());                         // attributes_count
        for (Iterator it = attributes.iterator(); it.hasNext();) { // attributes;
            ClassFile.AttributeInfo attribute = (ClassFile.AttributeInfo) it.next();
            attribute.store(dos);
        }
    }

    /**
     * Checks the code for consistency; updates the "maxStack" member.
     *
     * Notice: On inconsistencies, a "RuntimeException" is thrown (KLUDGE).
     */
    public void flowAnalysis(String functionName) {
        if (CodeContext.DEBUG) { 
            System.err.println("flowAnalysis(" + functionName + ")"); 
        }
        
        byte[] stackSizes = new byte[this.end.offset];
        Arrays.fill(stackSizes, CodeContext.UNEXAMINED);

        // Analyze flow from offset zero.
        this.flowAnalysis(
            functionName,
            this.code,       // code
            this.end.offset, // codeSize
            0,               // offset
            0,               // stackSize
            stackSizes       // stackSizes
        );

        // Analyze flow from exception handler entry points.
        int analyzedExceptionHandlers = 0;
        while (analyzedExceptionHandlers != this.exceptionTableEntries.size()) {
            for (int i = 0; i < this.exceptionTableEntries.size(); ++i) {
                ExceptionTableEntry exceptionTableEntry = (ExceptionTableEntry) this.exceptionTableEntries.get(i);
                if (stackSizes[exceptionTableEntry.startPC.offset] != CodeContext.UNEXAMINED) {
                    this.flowAnalysis(
                        functionName,         
                        this.code,                                          // code
                        this.end.offset,                                    // codeSize
                        exceptionTableEntry.handlerPC.offset,               // offset
                        stackSizes[exceptionTableEntry.startPC.offset] + 1, // stackSize
                        stackSizes                                          // stackSizes
                    );
                    ++analyzedExceptionHandlers;
                }
            }
        }

        // Check results and determine maximum stack size.
        this.maxStack = 0;
        for (int i = 0; i < stackSizes.length; ++i) {
            byte ss = stackSizes[i];
            if (ss == CodeContext.UNEXAMINED) {
                if (CodeContext.DEBUG) {
                    System.out.println(functionName + ": Unexamined code at offset " + i);
                    return;
                } else {
                    throw new RuntimeException(functionName + ": Unexamined code at offset " + i);
                }
            }
            if (ss > this.maxStack) this.maxStack = ss;
        }
    }

    private void flowAnalysis(
        String functionName,
        byte[] code,      // Bytecode
        int    codeSize,  // Size
        int    offset,    // Current PC
        int    stackSize, // Stack size on entry
        byte[] stackSizes // Stack sizes in code
    ) {
        for (;;) {
            if (CodeContext.DEBUG) System.out.println("Offset = " + offset + ", stack size = " + stackSize);

            // Check current bytecode offset.
            if (offset < 0 || offset >= codeSize) throw new RuntimeException(functionName + ": Offset out of range");

            // Have we hit an area that has already been analyzed?
            byte css = stackSizes[offset];
            if (css == stackSize) return; // OK.
            if (css == CodeContext.INVALID_OFFSET) throw new RuntimeException(functionName + ": Invalid offset");
            if (css != CodeContext.UNEXAMINED) {
                if (CodeContext.DEBUG) {
                    System.err.println(functionName + ": Operand stack inconsistent at offset " + offset + ": Previous size " + css + ", now " + stackSize);
                    return;
                } else {
                    throw new RuntimeException(functionName + ": Operand stack inconsistent at offset " + offset + ": Previous size " + css + ", now " + stackSize);
                }
            }
            stackSizes[offset] = (byte) stackSize;

            // Analyze current opcode.
            byte opcode = code[offset];
            int operandOffset = offset + 1;
            short props;
            if (opcode == Opcode.WIDE) {
                opcode = code[operandOffset++];
                props = Opcode.WIDE_OPCODE_PROPERTIES[0xff & opcode];
            } else {
                props = Opcode.OPCODE_PROPERTIES[0xff & opcode];
            }
            if (props == Opcode.INVALID_OPCODE) throw new RuntimeException(functionName + ": Invalid opcode " + (0xff & opcode) + " at offset " + offset);

            switch (props & Opcode.SD_MASK) {

            case Opcode.SD_M4:
            case Opcode.SD_M3:
            case Opcode.SD_M2:
            case Opcode.SD_M1:
            case Opcode.SD_P0:
            case Opcode.SD_P1:
            case Opcode.SD_P2:
                stackSize += (props & Opcode.SD_MASK) - Opcode.SD_P0;
                break;

            case Opcode.SD_0:
                stackSize = 0;
                break;

            case Opcode.SD_GETFIELD:
                --stackSize;
                /* FALL THROUGH */
            case Opcode.SD_GETSTATIC:
                stackSize += this.determineFieldSize((short) (
                    extract16BitValue(0, operandOffset, code)
                ));
                break;

            case Opcode.SD_PUTFIELD:
                --stackSize;
                /* FALL THROUGH */
            case Opcode.SD_PUTSTATIC:
                stackSize -= this.determineFieldSize((short) (
                    extract16BitValue(0, operandOffset, code)
                ));
                break;

            case Opcode.SD_INVOKEVIRTUAL:
            case Opcode.SD_INVOKESPECIAL:
            case Opcode.SD_INVOKEINTERFACE:
                --stackSize;
                /* FALL THROUGH */
            case Opcode.SD_INVOKESTATIC:
                stackSize -= this.determineArgumentsSize((short) (
                    extract16BitValue(0, operandOffset, code)
                ));
                break;

            case Opcode.SD_MULTIANEWARRAY:
                stackSize -= code[operandOffset + 2] - 1;
                break;

            default:
                throw new RuntimeException(functionName + ": Invalid stack delta");
            }

            if (stackSize < 0) {
                String msg = this.classFile.getThisClassName() + '.' + functionName + ": Operand stack underrun at offset " + offset;
                if (CodeContext.DEBUG) {
                    System.err.println(msg); 
                    return;
                } else {
                    throw new RuntimeException(msg); 
                }
            } 

            if (stackSize > Byte.MAX_VALUE) {
                String msg = this.classFile.getThisClassName() + '.' + functionName + ": Operand stack overflow at offset " + offset;
                if (CodeContext.DEBUG) {
                    System.err.println(msg); 
                    return;
                } else {
                    throw new RuntimeException(msg); 
                }
            }

            switch (props & Opcode.OP1_MASK) {

            case 0:
                ;
                break;

            case Opcode.OP1_SB:
            case Opcode.OP1_UB:
            case Opcode.OP1_CP1:
            case Opcode.OP1_LV1:
                ++operandOffset;
                break;

            case Opcode.OP1_SS:
            case Opcode.OP1_CP2:
            case Opcode.OP1_LV2:
                operandOffset += 2;
                break;

            case Opcode.OP1_BO2:
                if (CodeContext.DEBUG) {
                    System.out.println("Offset = " + offset);
                    System.out.println("Operand offset = " + operandOffset);
                    System.out.println(code[operandOffset]);
                    System.out.println(code[operandOffset + 1]);
                }
                this.flowAnalysis(
                    functionName,
                    code, codeSize,
                    extract16BitValue(offset, operandOffset, code),
                    stackSize,
                    stackSizes
                );
                operandOffset += 2;
                break;

            case Opcode.OP1_JSR:
                if (CodeContext.DEBUG) {
                    System.out.println("Offset = " + offset);
                    System.out.println("Operand offset = " + operandOffset);
                    System.out.println(code[operandOffset]);
                    System.out.println(code[operandOffset + 1]);
                }
                int targetOffset = extract16BitValue(offset, operandOffset, code);
                operandOffset += 2;
                if (stackSizes[targetOffset] == CodeContext.UNEXAMINED) {
                    this.flowAnalysis(
                        functionName,
                        code, codeSize,
                        targetOffset,
                        stackSize + 1,
                        stackSizes
                    );
                }
                break;

            case Opcode.OP1_BO4:
                this.flowAnalysis(
                    functionName,
                    code, codeSize,
                    extract32BitValue(offset, operandOffset, code),
                    stackSize, stackSizes
                );
                operandOffset += 4;
                break;

            case Opcode.OP1_LOOKUPSWITCH:
                while ((operandOffset & 3) != 0) ++operandOffset;
                this.flowAnalysis(
                    functionName,
                    code, codeSize,
                    extract32BitValue(offset, operandOffset, code),
                    stackSize, stackSizes
                );
                operandOffset += 4;
                
                int npairs = extract32BitValue(0, operandOffset, code);
                operandOffset += 4;
                
                for (int i = 0; i < npairs; ++i) {
                    operandOffset += 4; //skip match value
                    this.flowAnalysis(
                        functionName,
                        code, codeSize,
                        extract32BitValue(offset, operandOffset, code),
                        stackSize, stackSizes
                    );
                    operandOffset += 4; //advance over offset
                }
                break;

            case Opcode.OP1_TABLESWITCH:
                while ((operandOffset & 3) != 0) ++operandOffset;
                this.flowAnalysis(
                    functionName,
                    code, codeSize,
                    extract32BitValue(offset, operandOffset, code),
                    stackSize, stackSizes
                );
                operandOffset += 4;
                int low = extract32BitValue(offset, operandOffset, code);
                operandOffset += 4;
                int hi = extract32BitValue(offset, operandOffset, code);
                operandOffset += 4;
                for (int i = low; i <= hi; ++i) {
                    this.flowAnalysis(
                        functionName,
                        code, codeSize,
                        extract32BitValue(offset, operandOffset, code),
                        stackSize, stackSizes
                    );
                    operandOffset += 4;
                }
                break;

            default:
                throw new RuntimeException(functionName + ": Invalid OP1");
            }

            switch (props & Opcode.OP2_MASK) {

            case 0:
                ;
                break;

            case Opcode.OP2_SB:
                ++operandOffset;
                break;

            case Opcode.OP2_SS:
                operandOffset += 2;
                break;

            default:
                throw new RuntimeException(functionName + ": Invalid OP2");
            }

            switch (props & Opcode.OP3_MASK) {

            case 0:
                ;
                break;

            case Opcode.OP3_SB:
                ++operandOffset;
                break;

            default:
                throw new RuntimeException(functionName + ": Invalid OP3");
            }

            Arrays.fill(stackSizes, offset + 1, operandOffset, CodeContext.INVALID_OFFSET);

            if ((props & Opcode.NO_FALLTHROUGH) != 0) return;
            offset = operandOffset;
        }
    }
    
    /**
     * Extract a 16 bit value at offset in code and add bias to it
     * @param bias    an int to skew the final result by (useful for calculating relative offsets)
     * @param offset  the position in the code array to extract the bytes from
     * @param code    the array of bytes
     * @return an integer that treats the two bytes at position offset as an UNSIGNED SHORT
     */
    private int extract16BitValue(int bias, int offset, byte[] code) {
        int res = bias + (
                ((       code[offset  ]) << 8) +
                ((0xff & code[offset+1])     )
        );
        if (CodeContext.DEBUG) {
            System.out.println("extract16BitValue(bias, offset) = (" + bias +", " + offset + ")");
            System.out.println("bytes = {" + code[offset] + ", " + code[offset+1] + "}");
            System.out.println("result = " + res);
        }
        return res;
    }
    
    /**
     * Extract a 32 bit value at offset in code and add bias to it
     * @param bias    an int to skew the final result by (useful for calculating relative offsets)
     * @param offset  the position in the code array to extract the bytes from
     * @param code    the array of bytes
     * @return the 4 bytes at position offset + bias
     */
    private int extract32BitValue(int bias, int offset, byte[] code) {
        int res = bias + (
                ((       code[offset  ]) << 24) +
                ((0xff & code[offset+1]) << 16) +
                ((0xff & code[offset+2]) <<  8) +
                ((0xff & code[offset+3])      )
        );
        if (CodeContext.DEBUG) {
            System.out.println("extract32BitValue(bias, offset) = (" + bias +", " + offset + ")");
            System.out.println(
                    "bytes = {" + 
                    code[offset  ] + ", " + 
                    code[offset+1] + ", " + 
                    code[offset+2] + ", " + 
                    code[offset+3] + "}"
            );
            System.out.println("result = " + res);
        }
        return res;
    }
    
    /**
     * fixUp() all of the offsets and relocate() all relocatables
     */
    public void fixUpAndRelocate() {

        // We do this in a loop to allow relocatables to adjust the size
        // of things in the byte stream.  It is extremely unlikely, but possible
        // that a late relocatable will grow the size of the bytecode, and require
        // an earlier relocatable to switch from 32K mode to 64K mode branching
        do {
            fixUp();
        } while (!relocate());
    }

    /**
     * Fix up all offsets.
     */
    private void fixUp() {
        for (Offset o = this.beginning; o != this.end; o = o.next) {
            if (o instanceof FixUp) ((FixUp) o).fixUp();
        }
    }

    /**
     * Relocate all relocatables and aggregate their response into a single one
     * @return true if all of them relocated successfully
     *         false if any of them needed to change size
     */
    private boolean relocate() {
        boolean finished = true;
        for (int i = 0; i < this.relocatables.size(); ++i) {
            //do not terminate earlier so that everything gets a chance to grow in the first pass
            //changes the common case for this to be O(n) instead of O(n**2)
            boolean part =  ((Relocatable) this.relocatables.get(i)).relocate();
            finished = finished && part;
        }
        return finished;
    }

    /**
     * Analyse the descriptor of the Fieldref and return its size.
     */
    private int determineFieldSize(short idx) {
        ClassFile.ConstantFieldrefInfo    cfi   = (ClassFile.ConstantFieldrefInfo)    this.classFile.getConstantPoolInfo(idx);
        ClassFile.ConstantNameAndTypeInfo cnati = (ClassFile.ConstantNameAndTypeInfo) this.classFile.getConstantPoolInfo(cfi.getNameAndTypeIndex());
        ClassFile.ConstantUtf8Info        cui   = (ClassFile.ConstantUtf8Info)        this.classFile.getConstantPoolInfo(cnati.getDescriptorIndex());
        return Descriptor.size(cui.getString());
    }

    /**
     * Analyse the descriptor of the Methodref and return the sum of the
     * arguments' sizes minus the return value's size.
     */
    private int determineArgumentsSize(short idx) {
        ClassFile.ConstantPoolInfo cpi = this.classFile.getConstantPoolInfo(idx);
        ClassFile.ConstantNameAndTypeInfo nat = (ClassFile.ConstantNameAndTypeInfo) this.classFile.getConstantPoolInfo(
            cpi instanceof ClassFile.ConstantInterfaceMethodrefInfo ?
            ((ClassFile.ConstantInterfaceMethodrefInfo) cpi).getNameAndTypeIndex() :
            ((ClassFile.ConstantMethodrefInfo) cpi).getNameAndTypeIndex()
        );
        ClassFile.ConstantUtf8Info cui = (ClassFile.ConstantUtf8Info) this.classFile.getConstantPoolInfo(nat.getDescriptorIndex());
        String desc = cui.getString();

        if (desc.charAt(0) != '(') throw new RuntimeException("Method descriptor does not start with \"(\"");
        int i = 1;
        int res = 0;
        for (;;) {
            switch (desc.charAt(i++)) {
            case ')':
                return res - Descriptor.size(desc.substring(i));
            case 'B': case 'C': case 'F': case 'I': case 'S': case 'Z':
                res += 1;
                break;
            case 'D': case 'J':
                res += 2;
                break;
            case '[':
                res += 1;
                while (desc.charAt(i) == '[') ++i;
                if ("BCFISZDJ".indexOf(desc.charAt(i)) != -1) { ++i; break; }
                if (desc.charAt(i) != 'L') throw new RuntimeException("Invalid char after \"[\"");
                ++i;
                while (desc.charAt(i++) != ';');
                break;
            case 'L':
                res += 1;
                while (desc.charAt(i++) != ';');
                break;
            default:
                throw new RuntimeException("Invalid method descriptor");
            }
        }
    }

    /**
     * Inserts a sequence of bytes at the current insertion position. Creates
     * {@link LineNumberOffset}s as necessary.
     * 
     * @param lineNumber The line number that corresponds to the byte code, or -1
     * @param b
     */
    public void write(short lineNumber, byte[] b) {
        if (b.length == 0) return;

        INSERT_LINE_NUMBER_OFFSET:
        if (lineNumber != -1) {
            Offset o;
            for (o = this.currentInserter.prev; o != this.beginning; o = o.prev) {
                if (o instanceof LineNumberOffset) {
                    if (((LineNumberOffset) o).lineNumber == lineNumber) break INSERT_LINE_NUMBER_OFFSET;
                    break;
                }
            }
            LineNumberOffset lno = new LineNumberOffset(this.currentInserter.offset, lineNumber);
            lno.prev = this.currentInserter.prev;
            lno.next = this.currentInserter;
            this.currentInserter.prev.next = lno;
            this.currentInserter.prev = lno;
        }

        int ico = this.currentInserter.offset;
        if (this.end.offset + b.length <= this.code.length) {
            System.arraycopy(this.code, ico, this.code, ico + b.length, this.end.offset - ico);
        } else {
            byte[] oldCode = this.code;
            this.code = new byte[this.code.length + CodeContext.SIZE_INCREMENT];
            if (this.code.length >= 0xffff) throw new RuntimeException("Code attribute in class \"" + this.classFile.getThisClassName() + "\" grows beyond 64 KB");
            System.arraycopy(oldCode, 0, this.code, 0, ico);
            System.arraycopy(oldCode, ico, this.code, ico + b.length, this.end.offset - ico);
        }
        System.arraycopy(b, 0, this.code, ico, b.length);
        for (Offset o = this.currentInserter; o != null; o = o.next) o.offset += b.length;
    }

    /**
     * @param lineNumber The line number that corresponds to the byte code, or -1
     */
    public void writeShort(short lineNumber, int v) {
        this.write(lineNumber, new byte[] { (byte) (v >> 8), (byte) v });
    }

    /**
     * @param lineNumber The line number that corresponds to the byte code, or -1
     */
    public void writeBranch(short lineNumber, int opcode, final Offset dst) {
        this.relocatables.add(new Branch(opcode, dst));
        this.write(lineNumber, new byte[] { (byte) opcode, -1, -1 });
    }

    private static final Map EXPANDED_BRANCH_OPS = new HashMap(); // Map<Byte, Byte>
    static {
        //comparisons expand by doing a negated jump as follows:
        //  [if cond offset]
        //expands to 
        //  [if !cond skip_goto]
        //  [GOTO_W offset]
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ACMPEQ), new Byte(Opcode.IF_ACMPNE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ACMPNE), new Byte(Opcode.IF_ACMPEQ)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ICMPEQ), new Byte(Opcode.IF_ICMPNE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ICMPNE), new Byte(Opcode.IF_ICMPEQ)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ICMPGE), new Byte(Opcode.IF_ICMPLT)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ICMPLT), new Byte(Opcode.IF_ICMPGE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ICMPGT), new Byte(Opcode.IF_ICMPLE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IF_ICMPLE), new Byte(Opcode.IF_ICMPGT)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFEQ), new Byte(Opcode.IFNE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFNE), new Byte(Opcode.IFEQ)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFGE), new Byte(Opcode.IFLT)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFLT), new Byte(Opcode.IFGE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFGT), new Byte(Opcode.IFLE)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFLE), new Byte(Opcode.IFGT)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFNULL), new Byte(Opcode.IFNONNULL)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.IFNONNULL), new Byte(Opcode.IFNULL));
        
        // these merely expand to their wide version
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.GOTO), new Byte(Opcode.GOTO_W)); 
        EXPANDED_BRANCH_OPS.put(new Byte(Opcode.JSR), new Byte(Opcode.JSR_W)); 
    }

    private class Branch extends Relocatable {
        public Branch(int opcode, Offset destination) {
            this.opcode = opcode;
            this.source = CodeContext.this.newInserter();
            this.destination = destination;
            if (opcode == Opcode.JSR_W || opcode == Opcode.GOTO_W) {
                //no need to expand wide opcodes
                this.expanded = true;
            } else { 
                this.expanded = false;
            }
        }
        
        public boolean relocate() {
            if (this.destination.offset == Offset.UNSET) throw new RuntimeException("Cannot relocate branch to unset destination offset");
            int offset = this.destination.offset - this.source.offset;
            
            if (!this.expanded && (offset > Short.MAX_VALUE || offset < Short.MIN_VALUE)) {
                //we want to insert the data without skewing our source position,
                //so we will cache it and then restore it later.
                int pos = this.source.offset; 
                CodeContext.this.pushInserter(this.source); {
                    // promotion to a wide instruction only requires 2 extra bytes 
                    // everything else requires a new GOTO_W instruction after a negated if
                    CodeContext.this.write(
                        (short) -1, 
                        new byte[this.opcode == Opcode.GOTO ? 2 : this.opcode == Opcode.JSR ? 2 : 5]
                    );
                } CodeContext.this.popInserter();
                this.source.offset = pos;
                this.expanded = true;
                return false;
            }
            
            final byte[] ba;
            if (!this.expanded) {
                //we fit in a 16-bit jump
                ba = new byte[] { (byte) this.opcode, (byte) (offset >> 8), (byte) offset };
            } else {
                byte inverted = ((Byte) CodeContext.EXPANDED_BRANCH_OPS.get(
                        new Byte((byte) this.opcode))
                ).byteValue();
                if (this.opcode == Opcode.GOTO || this.opcode == Opcode.JSR) {
                    //  [GOTO offset]
                    //expands to 
                    //  [GOTO_W offset]
                    ba = new byte[] { 
                        (byte) inverted,
                        (byte) (offset >> 24), 
                        (byte) (offset >> 16), 
                        (byte) (offset >> 8), 
                        (byte) offset 
                    };
                } else {
                    //exclude the if-statement from jump target
                    //if jumping backwards this will increase the jump to go over it
                    //if jumping forwards this will decrease the jump by it
                    offset -= 3;
                            
                    //  [if cond offset]
                    //expands to 
                    //  [if !cond skip_goto]
                    //  [GOTO_W offset]
                    ba = new byte[] { 
                        (byte) inverted,
                        (byte) 0,
                        (byte) 8, //jump from this instruction past the GOTO_W
                        (byte) Opcode.GOTO_W, 
                        (byte) (offset >> 24), 
                        (byte) (offset >> 16), 
                        (byte) (offset >> 8), 
                        (byte) offset 
                    };
                }
            }
            System.arraycopy(ba, 0, CodeContext.this.code, this.source.offset, ba.length);
            return true;
        }
        
        private boolean expanded; //marks whether this has been expanded to account for a wide branch
        private final int opcode;
        private final Inserter source;
        private final Offset destination;
    }

    public void writeOffset(short lineNumber, Offset src, final Offset dst) {
        this.relocatables.add(new OffsetBranch(this.newOffset(), src, dst));
        this.write(lineNumber, new byte[] { -1, -1, -1, -1 });
    }

    private class OffsetBranch extends Relocatable {
        public OffsetBranch(Offset where, Offset source, Offset destination) {
            this.where       = where;
            this.source      = source;
            this.destination = destination;
        }
        public boolean relocate() {
            if (
                this.source.offset == Offset.UNSET ||
                this.destination.offset == Offset.UNSET
            ) throw new RuntimeException("Cannot relocate offset branch to unset destination offset");
            int offset = this.destination.offset - this.source.offset;
            byte[] ba = new byte[] {
                (byte) (offset >> 24),
                (byte) (offset >> 16),
                (byte) (offset >> 8),
                (byte) offset
            };
            System.arraycopy(ba, 0, CodeContext.this.code, this.where.offset, 4);
            return true;
        }
        private final Offset where, source, destination;
    }

    public Offset newOffset() {
        Offset o = new Offset();
        o.set();
        return o;
    }

    /**
     * Allocate an {@link Inserter}, set it to the current offset, and
     * insert it before the current offset.
     *
     * In clear text, this means that you can continue writing to the
     * "Code" attribute, then {@link #pushInserter(CodeContext.Inserter)} the
     * {@link Inserter}, then write again (which inserts bytes into the
     * "Code" attribute at the previously remembered position), and then
     * {@link #popInserter()}.
     */
    public Inserter newInserter() {
        Inserter i = new Inserter();
        i.set();
        return i;
    }

    public Inserter currentInserter() {
        return this.currentInserter;
    }

    /**
     * Remember the current {@link Inserter}, then replace it with the
     * new one.
     */
    public void pushInserter(Inserter ins) {
        if (ins.nextInserter != null) throw new RuntimeException("An Inserter can only be pushed once at a time");
        ins.nextInserter = this.currentInserter;
        this.currentInserter = ins;
    }

    /**
     * Replace the current {@link Inserter} with the remembered one (see
     * {@link #pushInserter(CodeContext.Inserter)}).
     */
    public void popInserter() {
        Inserter ni = this.currentInserter.nextInserter;
        if (ni == null) throw new RuntimeException("Code inserter stack underflow");
        this.currentInserter.nextInserter = null; // Mark it as "unpushed".
        this.currentInserter = ni;
    }

    /**
     * A class that represents an offset within a "Code" attribute.
     *
     * The concept of an "offset" is that if one writes into the middle of
     * a "Code" attribute, all offsets behind the insertion point are
     * automatically shifted.
     */
    public class Offset {
        int              offset = Offset.UNSET;
        Offset             prev = null, next = null;
        final static int UNSET = -1;

        /**
         * Set this "Offset" to the offset of the current inserter; insert
         * this "Offset" before the current inserter.
         */
        public void set() {
            if (this.offset != Offset.UNSET) throw new RuntimeException("Cannot \"set()\" Offset more than once");

            this.offset = CodeContext.this.currentInserter.offset;
            this.prev = CodeContext.this.currentInserter.prev;
            this.next = CodeContext.this.currentInserter;

            this.prev.next = this;
            this.next.prev = this;
        }
        public final CodeContext getCodeContext() { return CodeContext.this; }

        public String toString() {
            return CodeContext.this.classFile.getThisClassName() + ": " + this.offset;
        }
    }

    /**
     * Add another entry to the "exception_table" of this code attribute (see JVMS 4.7.3).
     * @param startPC
     * @param endPC
     * @param handlerPC
     * @param catchTypeFD
     */
    public void addExceptionTableEntry(
        Offset startPC,
        Offset endPC,
        Offset handlerPC,
        String catchTypeFD // null == "finally" clause
    ) {
        this.exceptionTableEntries.add(new ExceptionTableEntry(
            startPC,
            endPC,
            handlerPC,
            catchTypeFD == null ? (short) 0 : this.classFile.addConstantClassInfo(catchTypeFD)
        ));
    }

    /**
     * Representation of an entry in the "exception_table" of a "Code" attribute (see JVMS
     * 4.7.3).
     */
    private static class ExceptionTableEntry {
        public ExceptionTableEntry(
            Offset startPC,
            Offset endPC,
            Offset handlerPC,
            short  catchType
        ) {
            this.startPC   = startPC;
            this.endPC     = endPC;
            this.handlerPC = handlerPC;
            this.catchType = catchType;
        }
        private final Offset startPC, endPC, handlerPC;
        private final short  catchType; // 0 == "finally" clause
    }

    /**
     * A class that implements an insertion point into a "Code"
     * attribute.
     */
    public class Inserter extends Offset {
        private Inserter nextInserter = null; // null == not in "currentInserter" stack
    }

    public class LineNumberOffset extends Offset {
        private final int lineNumber;
        public LineNumberOffset(int offset, int lineNumber) {
            this.lineNumber = lineNumber;
            this.offset = offset;
        }
    }

    private abstract class Relocatable {
        /**
         * Relocate this object.
         * @return true if the relocation succeeded in place
         *         false if the relocation grew the number of bytes required
         */
        public abstract boolean relocate();
    }

    private short       localVariableArrayLength = 0;
    private final Stack savedLocalVariableArrayLengths = new Stack();
    private final List  relocatables = new ArrayList();

    /**
     * A throw-in interface that marks {@link CodeContext.Offset}s
     * as "fix-ups": During the execution of
     * {@link CodeContext#fixUp}, all "fix-ups" are invoked and
     * can do last touches to the code attribute.
     * <p>
     * This is currently used for inserting the "padding bytes" into the
     * TABLESWITCH and LOOKUPSWITCH instructions.
     */
    public interface FixUp {
        void fixUp();
    }
}
