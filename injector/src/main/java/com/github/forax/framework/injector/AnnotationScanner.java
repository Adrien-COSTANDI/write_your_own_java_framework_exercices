package com.github.forax.framework.injector;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class AnnotationScanner {
    static Stream<String> findAllJavaFilesInFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException(folder + "is not a folder");
        }
        Files.newDirectoryStream(folder).forEach(path -> System.out.println(path.getFileName()));

        return null;
    }
    // TODO
}
