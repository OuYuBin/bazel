// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.android.aapt2;

import com.android.builder.core.VariantType;
import com.android.repository.Revision;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.devtools.build.android.AaptCommandBuilder;
import com.google.devtools.build.android.AndroidDataSerializer;
import com.google.devtools.build.android.DataResourceXml;
import com.google.devtools.build.android.FullyQualifiedName;
import com.google.devtools.build.android.FullyQualifiedName.Factory;
import com.google.devtools.build.android.FullyQualifiedName.VirtualType;
import com.google.devtools.build.android.XmlResourceValues;
import com.google.devtools.build.android.xml.Namespaces;
import com.google.devtools.build.android.xml.ResourcesAttribute;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/** Invokes aapt2 to compile resources. */
public class ResourceCompiler {
  static class CompileError extends Aapt2Exception {

    protected CompileError(Throwable e) {
      super(e);
    }

    private CompileError() {
      super();
    }

    public static CompileError of(List<Throwable> compilationErrors) {
      final CompileError compileError = new CompileError();
      compilationErrors.forEach(compileError::addSuppressed);
      return compileError;
    }
  }

  private static final Logger logger = Logger.getLogger(ResourceCompiler.class.getName());

  private final CompilingVisitor compilingVisitor;

  private static class CompileTask implements Callable<List<Path>> {

    private final Path file;
    private final Path compiledResourcesOut;
    private final Path aapt2;
    private final Revision buildToolsVersion;
    private final boolean generatePseudoLocale;

    private CompileTask(
        Path file,
        Path compiledResourcesOut,
        Path aapt2,
        Revision buildToolsVersion,
        boolean generatePseudoLocale) {
      this.file = file;
      this.compiledResourcesOut = compiledResourcesOut;
      this.aapt2 = aapt2;
      this.buildToolsVersion = buildToolsVersion;
      this.generatePseudoLocale = generatePseudoLocale;
    }

    @Override
    public List<Path> call() throws Exception {
      logger.fine(
          new AaptCommandBuilder(aapt2)
              .forBuildToolsVersion(buildToolsVersion)
              .forVariantType(VariantType.LIBRARY)
              .add("compile")
              .add("-v")
              .add("--legacy")
              .when(generatePseudoLocale)
              .thenAdd("--pseudo-localize")
              .add("-o", compiledResourcesOut.toString())
              .add(file.toString())
              .execute("Compiling " + file));

      String type = file.getParent().getFileName().toString();
      String filename = file.getFileName().toString();

      List<Path> results = new ArrayList<>();
      if (type.startsWith("values")) {
        filename =
            (filename.indexOf('.') != -1 ? filename.substring(0, filename.indexOf('.')) : filename)
                + ".arsc";

        XMLEventReader xmlEventReader = null;
        try {
          // aapt2 compile strips out namespaces and attributes from the resources tag.
          // Read them here separately and package them with the other flat files.
          xmlEventReader =
              XMLInputFactory.newInstance()
                  .createXMLEventReader(new FileInputStream(file.toString()));

          // Iterate through the XML until we find a start element.
          // This should mimic xmlEventReader.nextTag() except that it also skips DTD elements.
          StartElement rootElement = null;
          while (xmlEventReader.hasNext()) {
            XMLEvent event = xmlEventReader.nextEvent();
            if (event.getEventType() != XMLStreamConstants.COMMENT
                && event.getEventType() != XMLStreamConstants.DTD
                && event.getEventType() != XMLStreamConstants.PROCESSING_INSTRUCTION
                && event.getEventType() != XMLStreamConstants.SPACE
                && event.getEventType() != XMLStreamConstants.START_DOCUMENT) {

              // If the event should not be skipped, try parsing it as a start element here.
              // If the event is not a start element, an appropriate exception will be thrown.
              rootElement = event.asStartElement();
              break;
            }
          }

          if (rootElement == null) {
            throw new Exception("No start element found in resource XML file: " + file.toString());
          }

          Iterator<Attribute> attributeIterator =
              XmlResourceValues.iterateAttributesFrom(rootElement);

          if (attributeIterator.hasNext()) {
            results.add(createAttributesProto(type, filename, attributeIterator));
          }
        } finally {
          if (xmlEventReader != null) {
            xmlEventReader.close();
          }
        }
      }

      final Path compiledResourcePath =
          compiledResourcesOut.resolve(type + "_" + filename + ".flat");
      Preconditions.checkArgument(
          Files.exists(compiledResourcePath),
          "%s does not exists after aapt2 ran.",
          compiledResourcePath);
      results.add(compiledResourcePath);
      return results;
    }

    private Path createAttributesProto(
        String type, String filename, Iterator<Attribute> attributeIterator) throws IOException {

      AndroidDataSerializer serializer = AndroidDataSerializer.create();
      final Path resourcesAttributesPath =
          compiledResourcesOut.resolve(type + "_" + filename + ".attributes");

      while (attributeIterator.hasNext()) {
        Attribute attribute = attributeIterator.next();
        String namespaceUri = attribute.getName().getNamespaceURI();
        String localPart = attribute.getName().getLocalPart();
        String prefix = attribute.getName().getPrefix();
        QName qName = new QName(namespaceUri, localPart, prefix);

        Namespaces namespaces = Namespaces.from(qName);
        String attributeName = namespaceUri.isEmpty() ? localPart : prefix + ":" + localPart;

        Factory fqnFactory = Factory.fromDirectoryName(type);
        FullyQualifiedName fqn =
            fqnFactory.create(VirtualType.RESOURCES_ATTRIBUTE, qName.toString());
        ResourcesAttribute resourceAttribute =
            ResourcesAttribute.of(fqn, attributeName, attribute.getValue());
        DataResourceXml resource =
            DataResourceXml.createWithNamespaces(file, resourceAttribute, namespaces);

        serializer.queueForSerialization(fqn, resource);
      }

      serializer.flushTo(resourcesAttributesPath);
      return resourcesAttributesPath;
    }

    @Override
    public String toString() {
      return "ResourceCompiler.CompileTask(" + file + ")";
    }
  }

  private static class CompilingVisitor extends SimpleFileVisitor<Path> {

    private final ListeningExecutorService executorService;
    private final Path compiledResources;
    private final Map<Path, Path> pathToProcessed = new LinkedHashMap<>();
    private final Path aapt2;
    private final Revision buildToolsVersion;
    private final boolean generatePseudoLocale;

    public CompilingVisitor(
        ListeningExecutorService executorService,
        Path compiledResources,
        Path aapt2,
        Revision buildToolsVersion,
        boolean generatePseudoLocale) {
      this.executorService = executorService;
      this.compiledResources = compiledResources;
      this.aapt2 = aapt2;
      this.buildToolsVersion = buildToolsVersion;
      this.generatePseudoLocale = generatePseudoLocale;
    }

    static final Pattern REGION_PATTERN =
        Pattern.compile("(sr[_\\-]r?latn)|(es[_\\-]r?419)", Pattern.CASE_INSENSITIVE);

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      // Ignore directories and "hidden" files that start with .
      if (!Files.isDirectory(file) && !file.getFileName().toString().startsWith(".")) {
        // Creates a relative output path based on the input path under the
        // compiledResources path.
        Path outputDirectory =
            Files.createDirectories(
                compiledResources.resolve(
                    (file.isAbsolute() ? file.getRoot().relativize(file) : file)
                        .getParent()
                        .getParent()));

        Path maybeFixedPath =
            file.getParent()
                .getParent()
                .resolve(
                    maybeFixRegion(file.getParent().getFileName()).resolve(file.getFileName()));

        if (!(maybeFixedPath.equals(file))) {
          if (!Files.exists(maybeFixedPath)) {
            logger.severe(
                String.format(
                    "The locale identifier in %s is not supported by aapt2. Converting to %s. "
                        + "This will be an error in the future.",
                    file, maybeFixedPath));
            // Only use the processed path if doesn't exist. If it exists, there are is already
            // resources for that region.
            pathToProcessed.put(
                Files.copy(
                    file,
                    Files.createDirectories(
                            outputDirectory.resolve(maybeFixedPath.getParent().getFileName()))
                        .resolve(file.getFileName())),
                outputDirectory);
          } else {
            logger.severe(
                String.format(
                    "Skipping resource compilation for %s: it has the same qualifiers as %s."
                        + " The locale identifier is not supported by aapt2."
                        + " This will be an error in the future.",
                    file, maybeFixedPath));
          }
        } else {
          pathToProcessed.put(file, outputDirectory);
        }
      }
      return super.visitFile(file, attrs);
    }

    /** Aapt cannot interpret these regions so we rename them to get them to compile. */
    static Path maybeFixRegion(Path p) {
      Matcher matcher = REGION_PATTERN.matcher(p.toString());
      if (!matcher.find()) {
        return p;
      }
      StringBuffer fixedConfiguration = new StringBuffer();
      matcher.appendReplacement(
          fixedConfiguration, matcher.group(2) == null ? "b+sr+Latn" : "b+es+419");
      return p.getFileSystem().getPath(matcher.appendTail(fixedConfiguration).toString());
    }

    List<Path> getCompiledArtifacts() {
      List<ListenableFuture<List<Path>>> tasks = new ArrayList<>();
      for (Entry<Path, Path> entry : pathToProcessed.entrySet()) {
        tasks.add(
            executorService.submit(
                new CompileTask(
                    entry.getKey(),
                    entry.getValue(),
                    aapt2,
                    buildToolsVersion,
                    generatePseudoLocale)));
      }

      ImmutableList.Builder<Path> builder = ImmutableList.builder();
      List<Throwable> compilationErrors = new ArrayList<>();
      for (ListenableFuture<List<Path>> task : tasks) {
        try {
          builder.addAll(task.get());
        } catch (InterruptedException | ExecutionException e) {
          compilationErrors.add(Optional.ofNullable(e.getCause()).orElse(e));
        }
      }
      if (compilationErrors.isEmpty()) {
        return builder.build();
      }
      throw CompileError.of(compilationErrors);
    }
  }

  /** Creates a new {@link ResourceCompiler}. */
  public static ResourceCompiler create(
      ListeningExecutorService executorService,
      Path compiledResources,
      Path aapt2,
      Revision buildToolsVersion,
      boolean generatePseudoLocale) {
    return new ResourceCompiler(
        new CompilingVisitor(
            executorService, compiledResources, aapt2, buildToolsVersion, generatePseudoLocale));
  }

  private ResourceCompiler(CompilingVisitor compilingVisitor) {
    this.compilingVisitor = compilingVisitor;
  }

  /** Adds a task to compile the directory using aapt2. */
  public void queueDirectoryForCompilation(Path resource) throws IOException {
    Files.walkFileTree(resource, compilingVisitor);
  }

  /** Returns all paths of the aapt2 compiled resources. */
  public List<Path> getCompiledArtifacts() throws InterruptedException, ExecutionException {
    return compilingVisitor.getCompiledArtifacts();
  }
}
