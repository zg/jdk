/*
 * Copyright (c) 2008, 2012, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2012 SAP AG. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.nio.fs;

import java.nio.file.spi.FileTypeDetector;
import java.io.IOException;

/**
 * AIX implementation of FileSystemProvider
 */

public class AixFileSystemProvider extends UnixFileSystemProvider {
    public AixFileSystemProvider() {
        super();
    }

    @Override
    AixFileSystem newFileSystem(String dir) {
        return new AixFileSystem(this, dir);
    }

    /**
     * @see sun.nio.fs.UnixFileSystemProvider#getFileStore(sun.nio.fs.UnixPath)
     */
    @Override
    AixFileStore getFileStore(UnixPath path) throws IOException {
        return new AixFileStore(path);
    }

    @Override
    FileTypeDetector getFileTypeDetector() {
        return new GnomeFileTypeDetector();
    }
}
