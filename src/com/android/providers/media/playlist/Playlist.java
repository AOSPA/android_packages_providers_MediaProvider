/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.playlist;

import static com.android.providers.media.util.Logging.TAG;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representation of a playlist of multiple items, each represented by their
 * {@link Path}. Note that identical items may be repeated within a playlist,
 * and that strict ordering is maintained.
 * <p>
 * This representation is agnostic to file format, but you can {@link #read}
 * playlist files into memory, modify them, and then {@link #write} them back
 * into playlist files. This design allows you to easily convert between
 * playlist file formats by reading one format and writing to another.
 */
public class Playlist {
    private final ArrayList<Path> mItems = new ArrayList<>();

    public List<Path> asList() {
        return Collections.unmodifiableList(mItems);
    }

    public void clear() {
        mItems.clear();
    }

    public void read(@NonNull File file) throws IOException {
        clear();
        try (InputStream in = new FileInputStream(file)) {
            PlaylistPersister.resolvePersister(file).read(in, mItems);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "Treating missing file as empty playlist");
        }
    }

    public void write(@NonNull File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            PlaylistPersister.resolvePersister(file).write(out, mItems);
        }
    }

    /**
     * Add the given playlist item at the nearest valid index.
     */
    public int add(int index, Path item) {
        // Gracefully handle items beyond end
        final int size = mItems.size();
        index = Math.min(index, size);

        mItems.add(index, item);
        return index;
    }

    /**
     * Move an existing playlist item from the nearest valid index to the
     * nearest valid index.
     */
    public int move(int from, int to) {
        // Gracefully handle items beyond end
        final int size = mItems.size();
        from = Math.min(from, size - 1);
        to = Math.min(to, size - 1);

        final Path item = mItems.remove(from);
        mItems.add(to, item);
        return to;
    }

    /**
     * Remove an existing playlist item from the nearest valid index.
     */
    public int remove(int index) {
        // Gracefully handle items beyond end
        final int size = mItems.size();
        index = Math.min(index, size - 1);

        mItems.remove(index);
        return index;
    }
}
