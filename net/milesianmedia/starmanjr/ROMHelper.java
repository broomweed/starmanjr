package net.milesianmedia.starmanjr;

import java.io.*;
import java.util.regex.Pattern;
import net.milesianmedia.starmanjr.TextProcessor;

public class ROMHelper {

    private FileInputStream fis;
    private String[] charTable = new String[256];     // has to be String[] and not char[] because some entries (BREAK, DOUBLEZERO, etc.)
                            // are longer than 1 character :(

    public ROMHelper (File chtbl) {
        charTable = tableFromFile(chtbl);
    }

    private String[] tableFromFile (File f) {
        String[] retVal = new String[256];
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String currLine;
            while ((currLine = br.readLine()) != null) {
                String entry = currLine.split(" ")[1];
                // if it's not an underscore, add it at the right position
                if (entry.compareTo("_") != 0) {
                    if (entry.length() > 1 && !entry.startsWith("[")) {
                        // if this, it's one of the control codes written without [s
                        retVal[Integer.parseInt(currLine.split(" ")[0], 16)] = "[" + entry + "]";
                    } else {
                        if (entry.compareTo("[03]") == 0) {
                            // "[03 xx]", so not "[03]<unknown character>"
                            retVal[Integer.parseInt(currLine.split(" ")[0], 16)] = "[03 ";
                        } else {
                            // otherwise it's all good, bro
                            retVal[Integer.parseInt(currLine.split(" ")[0], 16)] = entry;
                        }
                    }
                } else {
                    // if it is an underscore, it's actually a space
                    retVal[Integer.parseInt(currLine.split(" ")[0], 16)] = " ";
                }
            }
            br.close();
            return retVal;
        } catch (FileNotFoundException e) {
            System.out.println("couldn't find file at "+f.getPath());
            return null;
        } catch (IOException e) {
            System.out.println("An I/O error occurred. Please try again.");
            return null;
        }
    }

    private byte[] fileToByteArray (File f) {
        try {
            fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(b)) != -1) {
                bos.write(b, 0, bytesRead);
            }
            return bos.toByteArray();
        } catch (FileNotFoundException e) {
            System.out.println("File not found!");
            return null;
        } catch (IOException e) {
            System.out.println("I/O error!");
            return null;
        }
    }

    // reads from a ROM and outputs a script file
    public void readFromROM (File rom, String scriptPath) throws IOException {
        // turn into byte array
        System.out.print("Converting file to byte array...");
        byte[] bytes = fileToByteArray(rom);
        System.out.println("done, size is "+bytes.length);
        // locate end of meaningful data in file
        System.out.print("Finding end of script pointer data in ROM...");
        int pointerStart = 0xF27A90;
        int eof = pointerStart; // to look at the 08/00 part
        int precision = 9;
        while (precision > -1) {
            while ((bytes[eof+3] & 0xFF) == 0x08 || (bytes[eof+3] & 0xFF) == 0x00) {
                eof += 4 << precision;
            }
            eof -= 4 << precision;
            precision--;
        }
        eof += 4 << (precision+1);
        System.out.println("done, EOF is at 0x"+Integer.toHexString(eof).toUpperCase()+" (not really, but will correct itself)");
        // now parse the thingy and create a file
        System.out.print("Parsing data and creating script file...");
        PrintWriter pw = new PrintWriter(new FileWriter(scriptPath));
        String writeable = "";
        int lineIndex = 1;
        boolean writingCC = false;
        // yay for loops
        for (int ptrLoc = pointerStart; ptrLoc < eof; ptrLoc += 4) {
            // first look up the pointer address
            int address = 0;
            address += bytes[ptrLoc] & 0xFF;
            address += (bytes[ptrLoc+1] & 0xFF) << 8;
            address += (bytes[ptrLoc+2] & 0xFF) << 16;
            if (address == 0x100000) break; // this means we've reached the similar-looking-to-the-program-but-not-script-pointer area of the ROM
            if (address != 0x00) {
                // it's a "real" line
                for (int loc = address; ; loc++) {
                    if (!writingCC) {
                        if (bytes[loc] == 0x0) {
                            // EOL
                            pw.println(TextProcessor.addNumPrefix(writeable, lineIndex));
                            pw.println(TextProcessor.addNumEPrefix(writeable, lineIndex));
                            writeable = "";
                            lineIndex++;
                            break;
                            /*\
                            |*| FUN FACT TIME:
                            |*| it has to do 'break' rather than having a second term in the for loop,
                            |*| because it does something if it's the end of the line, and there's no
                            |*| way to distinguish between the beginning of a line and the beginning of
                            |*| the next line
                            \*/
                        } else if (bytes[loc] == 0x03 && bytes[loc+1] == 0x02) {
                            // [03 02] = [PAUSE]
                            writeable += "[PAUSE]";
                            loc++;
                            // (to skip over the 02)
                        } else if (bytes[loc] == 0x03) {
                            // first half of control code
                            writeable += "[03 ";
                            writingCC = true;
                        } else {
                            // normal character
                            writeable += charTable[bytes[loc] & 0xFF];
                        }
                    } else {
                        // second half of control code
                        String digit = Integer.toHexString(bytes[loc]&0xFF).toUpperCase();
                        if (digit.length() == 1) {
                            digit = "0"+digit;
                        }
                        writeable += digit + "]";
                        writingCC = false;
                    }

                }
            } else {
                // it's a 0 pointer
                pw.println(TextProcessor.addNumPrefix(writeable, lineIndex));
                pw.println(TextProcessor.addNumEPrefix(writeable, lineIndex));
                writeable = "";
                lineIndex++;
            }
        }
        pw.close();
        System.out.println("done");
    }

    public void writeToROM (File baseROM, File romToWrite, String[] lines) throws FileNotFoundException, IOException {
        // this is basically a straight-up port of Tomato's insert.c to Java, except it represents the file
        // as an array of bytes and flushes them all at the end... not totally sure how Tomato's does it
        // but I'm pretty sure it's not that
        System.out.println("Writing to ROM...");
        byte[] romAsBytes = fileToByteArray(baseROM);
        int start = 0xF7EA00;
        int pointerStart = 0xF27A90;
        int offset = 0;
        int numberOfLines = 0;
        int errors = 0;
        // this is one without brackets on control codes, for ease of use when writing hex bytes
        String[] charTableNB = charTable;
        for (int i = 0; i < charTable.length; i++) {
            if (charTableNB[i].startsWith("[")) {
                charTableNB[i] = charTableNB[i].substring(1, charTableNB[i].length()-1);
            }
        }
        if (!romToWrite.exists()) {
            System.out.print("File doesn't exist. Creating...");
            romToWrite.createNewFile();
            System.out.println("done");
        }
        System.out.println("== CONVERTING TABLE ==");
        for (int lineNum = 1; lineNum < lines.length; lineNum++) {
            String lineToWrite = TextProcessor.getEditLine(lines, lineNum);
            String hexLineNum = Integer.toString(lineNum-1, 16).toUpperCase();
            while (hexLineNum.length() < 3) {
                hexLineNum = "0"+hexLineNum;
            }
            hexLineNum = hexLineNum+"-E";
            int lineLength = 0;
            for (int i = 0; i < lineToWrite.length(); i++) {
                byte byteToWrite = (byte)0x00;
                boolean writeByte = false;
                try {
                if (lineToWrite.charAt(i) != '[') {
                    // search for it in the table
                    boolean foundChar = false;
                    for (int search = 0; search < charTable.length; search++) {
                        //System.out.print("checking with "+charTable[search]+" ");
                        //if (charTable[search].split(" ")[1].compareTo(String.valueOf(lineToWrite.charAt(i))) == 0) {
                        if (charTable[search].compareTo(String.valueOf(lineToWrite.charAt(i))) == 0) {
                            //byteToWrite = (byte) Integer.parseInt(charTable[search].split(" ")[0], 16);
                            byteToWrite = (byte) search;
                            foundChar = true;
                            writeByte = true;
                            //System.out.println("finally "+charTable[search]);
                            break;
                        }
                    }
                    if (!foundChar) {
                        System.out.println("!! Invalid character "+lineToWrite.charAt(i)+" in line "+hexLineNum+", skipping");
                        errors++;
                    }
                } else {
                    // in the words of Tomato: "now we gotta parse the control codes, what a pain"
                    String cc = "";
                    do {
                        i++;
                        cc += lineToWrite.charAt(i);
                    } while (lineToWrite.charAt(i) != ']');
                    // remove trailing ]
                    cc = cc.substring(0, cc.length() - 1);
                    // convert to uppercase for ease of use
                    cc = cc.toUpperCase();
                    if (cc.startsWith("03")) {
                        romAsBytes[start+offset] = 0x03;
                        lineLength++;
                        offset++;
                        try {
                            byteToWrite = (byte) Integer.parseInt(cc.split(" ")[1], 16);
                            writeByte = true;
                        } catch (NumberFormatException e) {
                            System.out.println("!! Bracket problem at line "+hexLineNum+", please fix before compiling again");
                            errors++;
                        }
                    } else if (cc.compareTo("PAUSE") == 0) {
                        // ugh this is so hacky but that's the way that the table works, I guess, & we have to keep backwards compatibility
                        romAsBytes[start+offset] = 0x03;
                        lineLength++;
                        offset++;
                        // pause is 03 02
                        byteToWrite = 0x02;
                        writeByte = true;
                    } else if (cc.split(" ")[0].length() == 2 && Pattern.matches("[0-9A-F][0-9A-F]", cc.split(" ")[0])) {
                        // starts with a 2-digit hex number, hopefully the rest are also 2-digit hex numbers
                        String[] controlCodeHexes = cc.split(" ");
                        for (int j = 0; j < controlCodeHexes.length; j++) {
                            try {
                                if (controlCodeHexes[j].length() == 2) {
                                    // it's a 2-digit hex number!!
                                    byte b = (byte) Integer.parseInt(controlCodeHexes[j], 16);
                                    romAsBytes[start+offset] = b;
                                    lineLength++;
                                    offset++;
                                } else {
                                    // it's the wrong length, but we'll just pretend it's the same as failing the try-catch
                                    System.out.println("!! Invalid hex-insert CC ["+controlCodeHexes[j]+"] in line "+hexLineNum+", skipping");
                                    errors++;
                                }
                            } catch (NumberFormatException e) {
                                // it's not even a hex number, what are you thinking
                                System.out.println("!! Invalid hex-insert CC ["+controlCodeHexes[j]+"] in line "+hexLineNum+", skipping");
                                errors++;
                            }
                        }
                    } else {
                        // search through the table again...
                        boolean foundChar = false;
                        for (int search = 0; search < charTable.length; search++) {
                            if (charTableNB[search].compareTo(cc) == 0 && search != 0x99) {
                                //byteToWrite = (byte) Integer.parseInt(charTable[search].split(" ")[0], 16);i <-- table doesn't work like this
                                byteToWrite = (byte) search;
                                foundChar = true;
                                writeByte = true;
                                break;
                            }
                        }
                        if (!foundChar) {
                            System.out.println("!! Invalid CC ["+cc+"] in line "+hexLineNum+", skipping");
                            errors++;
                        }
                    }
                }
                } catch (Exception e) {
                    System.out.println("!! Generic problem at line "+hexLineNum+", please fix before compiling again");
                    errors++;
                }
                // at the end of every character-finding loop
                if (writeByte) {
                    romAsBytes[start+offset] = byteToWrite;
                    lineLength++;
                    offset++;
                }
            }
            if (lineLength >= 1) {
                // write a [00] at the end, and calculate the pointer; the bitshifting stuff is shamelessly stolen from Tomato
                int lineLoc = start + offset - lineLength + 0x08000000;
                romAsBytes[start+offset] = 0x00;
                offset++;
                romAsBytes[pointerStart+numberOfLines*4] = (byte) (lineLoc & 0x000000FF);
                romAsBytes[pointerStart+numberOfLines*4 + 1] = (byte) ((lineLoc & 0x0000FF00) >> 8);
                romAsBytes[pointerStart+numberOfLines*4 + 2] = (byte) ((lineLoc & 0x00FF0000) >> 16);
                romAsBytes[pointerStart+numberOfLines*4 + 3] = (byte) (lineLoc >> 24);
            }
            numberOfLines++;
        }
        System.out.print("Writing array to file...");
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(romToWrite));
        bos.write(romAsBytes, 0, romAsBytes.length);
        System.out.println("done");
        if (errors > 0) {
            System.out.println(errors+" errors were found. Your ROM has been compiled, but you may want to fix these things.");
        }
    }

}
