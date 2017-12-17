/*
 * Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 6575373
 * @summary verify segment limit
 * @compile -XDignore.symbol.file Utils.java SegmentLimit.java
 * @run main SegmentLimit
 * @author ksrini
 */

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/*
 * Run this against a large jar file. The packer should generate only
 * one segment, parse the output of the packer to verify if this is indeed true.
 */

public class SegmentLimit {

    public static void main(String... args) {
        File out = new File("test" + Utils.PACK_FILE_EXT);
        out.delete();
        runPack200(out);
    }

    static void runPack200(File outFile) { 
        File sdkHome = Utils.JavaSDK;
	File testJar = new File(new File(sdkHome, "lib"), "tools.jar");

        System.out.println("using pack200: " + Utils.getPack200Cmd());

        List<String> cmdsList = new ArrayList<String>();
        cmdsList.add(Utils.getPack200Cmd());
        cmdsList.add("--effort=1");
        cmdsList.add("--verbose");
        cmdsList.add("--no-gzip");
	cmdsList.add("--segment-limit=-1");
        cmdsList.add(outFile.getName());
        cmdsList.add(testJar.getAbsolutePath());
        List<String> outList = Utils.runExec(cmdsList);

        int count = 0;
        for (String line : outList) {
            System.out.println(line);
            if (line.matches(".*Transmitted.*files of.*input bytes in a segment of.*bytes")) {
                count++;
            }
        }
        if (count != 1) {
            throw new Error("test fails: check for 0 or multiple segments");
        }
    }
}

