// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp.proto;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode.TARGET;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapBuilder;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.rules.cpp.AspectLegalCppSemantics;
import com.google.devtools.build.lib.rules.cpp.CcCommon;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper;
import com.google.devtools.build.lib.rules.cpp.CcCompilationHelper.CompilationInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingHelper;
import com.google.devtools.build.lib.rules.cpp.CcLinkingHelper.LinkingInfo;
import com.google.devtools.build.lib.rules.cpp.CcToolchain;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainProvider;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppHelper;
import com.google.devtools.build.lib.rules.cpp.CppRuleClasses;
import com.google.devtools.build.lib.rules.cpp.CppSemantics;
import com.google.devtools.build.lib.rules.cpp.TransitiveLipoInfoProvider;
import com.google.devtools.build.lib.rules.cpp.transitions.LipoContextCollectorTransition;
import com.google.devtools.build.lib.rules.proto.ProtoCommon;
import com.google.devtools.build.lib.rules.proto.ProtoCompileActionBuilder;
import com.google.devtools.build.lib.rules.proto.ProtoCompileActionBuilder.ToolchainInvocation;
import com.google.devtools.build.lib.rules.proto.ProtoConfiguration;
import com.google.devtools.build.lib.rules.proto.ProtoLangToolchainProvider;
import com.google.devtools.build.lib.rules.proto.ProtoSourceFileBlacklist;
import com.google.devtools.build.lib.rules.proto.ProtoSupportDataProvider;
import com.google.devtools.build.lib.rules.proto.SupportData;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.Collection;

/** Part of the implementation of cc_proto_library. */
public abstract class CcProtoAspect extends NativeAspectClass implements ConfiguredAspectFactory {

  private static final String PROTO_TOOLCHAIN_ATTR = ":aspect_cc_proto_toolchain";

  private static final LabelLateBoundDefault<?> PROTO_TOOLCHAIN_LABEL =
      LabelLateBoundDefault.fromTargetConfiguration(
          ProtoConfiguration.class,
          Label.parseAbsoluteUnchecked("@com_google_protobuf//:cc_toolchain"),
          (rule, attributes, protoConfig) -> protoConfig.protoToolchainForCc());

  private final CppSemantics cppSemantics;
  private final LabelLateBoundDefault<?> ccToolchainAttrValue;
  private final Label ccToolchainType;

  protected CcProtoAspect(AspectLegalCppSemantics cppSemantics, RuleDefinitionEnvironment env) {
    this.cppSemantics = cppSemantics;
    this.ccToolchainAttrValue = CppRuleClasses.ccToolchainAttribute(env);
    this.ccToolchainType = CppRuleClasses.ccToolchainTypeAttribute(env);
  }

  @Override
  public ConfiguredAspect create(
      ConfiguredTargetAndData ctadBase, RuleContext ruleContext, AspectParameters parameters)
      throws InterruptedException {
    // Get SupportData, which is provided by the proto_library rule we attach to.
    SupportData supportData =
        checkNotNull(ctadBase.getConfiguredTarget().getProvider(ProtoSupportDataProvider.class))
            .getSupportData();

    try {
      ConfiguredAspect.Builder result = new ConfiguredAspect.Builder(this, parameters, ruleContext);
      new Impl(ruleContext, supportData, cppSemantics).addProviders(result);
      return result.build();
    } catch (RuleErrorException e) {
      ruleContext.ruleError(e.getMessage());
      return null;
    }
  }

  @Override
  public AspectDefinition getDefinition(AspectParameters aspectParameters) {
    AspectDefinition.Builder result =
        new AspectDefinition.Builder(this)
            .propagateAlongAttribute("deps")
            .requiresConfigurationFragments(CppConfiguration.class, ProtoConfiguration.class)
            .requireProviders(ProtoSupportDataProvider.class)
            .addRequiredToolchains(ccToolchainType)
            .add(
                attr(PROTO_TOOLCHAIN_ATTR, LABEL)
                    .mandatoryNativeProviders(ImmutableList.of(ProtoLangToolchainProvider.class))
                    .value(PROTO_TOOLCHAIN_LABEL))
            .add(
                attr(CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME, LABEL)
                    .value(ccToolchainAttrValue))
            .add(
                attr(TransitiveLipoInfoProvider.LIPO_CONTEXT_COLLECTOR, LABEL)
                    .cfg(LipoContextCollectorTransition.INSTANCE)
                    .value(CppRuleClasses.LIPO_CONTEXT_COLLECTOR)
                    .skipPrereqValidatorCheck());

    return result.build();
  }

  private static class Impl {

    private final TransitiveInfoProviderMap ccLibraryProviders;
    private final ProtoCcHeaderProvider headerProvider;
    private final ImmutableMap<String, NestedSet<Artifact>> outputGroups;

    private final RuleContext ruleContext;
    private final SupportData supportData;
    private final CppSemantics cppSemantics;
    private final NestedSetBuilder<Artifact> filesBuilder;

    Impl(RuleContext ruleContext, SupportData supportData, CppSemantics cppSemantics)
        throws RuleErrorException, InterruptedException {
      this.ruleContext = ruleContext;
      this.supportData = supportData;
      this.cppSemantics = cppSemantics;
      FeatureConfiguration featureConfiguration = getFeatureConfiguration(supportData);
      ProtoConfiguration protoConfiguration = ruleContext.getFragment(ProtoConfiguration.class);

      CcCompilationHelper compilationHelper = initializeCompilationHelper(featureConfiguration);

      // Compute and register files generated by this proto library.
      Collection<Artifact> outputs = new ArrayList<>();
      if (areSrcsBlacklisted()) {
        registerBlacklistedSrcs(supportData, compilationHelper);
        headerProvider = null;
      } else if (supportData.hasProtoSources()) {
        Collection<Artifact> headers =
            getOutputFiles(supportData, protoConfiguration.ccProtoLibraryHeaderSuffixes());
        Collection<Artifact> sources =
            getOutputFiles(supportData, protoConfiguration.ccProtoLibrarySourceSuffixes());
        outputs.addAll(headers);
        outputs.addAll(sources);

        compilationHelper.addSources(sources);
        compilationHelper.addPublicHeaders(headers);

        NestedSetBuilder<Artifact> publicHeaderPaths = NestedSetBuilder.stableOrder();
        publicHeaderPaths.addAll(headers);
        headerProvider = new ProtoCcHeaderProvider(publicHeaderPaths.build());
      } else {
        // If this proto_library doesn't have sources, it provides the combined headers of all its
        // direct dependencies. Thus, if a direct dependency does have sources, the generated files
        // are also provided by this library. If a direct dependency does not have sources, it will
        // do the same thing, so that effectively this library looks through all source-less
        // proto_libraries and provides all generated headers of the proto_libraries with sources
        // that it depends on.
        NestedSetBuilder<Artifact> transitiveHeaders = NestedSetBuilder.stableOrder();
        for (ProtoCcHeaderProvider provider :
            ruleContext.getPrerequisites("deps", TARGET, ProtoCcHeaderProvider.class)) {
          compilationHelper.addPublicTextualHeaders(provider.getHeaders());
          transitiveHeaders.addTransitive(provider.getHeaders());
        }
        headerProvider = new ProtoCcHeaderProvider(transitiveHeaders.build());
      }

      filesBuilder = NestedSetBuilder.stableOrder();
      filesBuilder.addAll(outputs);
      createProtoCompileAction(supportData, outputs);

      CompilationInfo compilationInfo = compilationHelper.compile();
      LinkingInfo linkingInfo =
          initializeLinkingHelper(featureConfiguration)
              .link(
                  compilationInfo.getCcCompilationOutputs(),
                  compilationInfo.getCcCompilationContextInfo());

      ccLibraryProviders =
          new TransitiveInfoProviderMapBuilder()
              .addAll(compilationInfo.getProviders())
              .addAll(linkingInfo.getProviders())
              .build();
      outputGroups =
          ImmutableMap.copyOf(
              CcCommon.mergeOutputGroups(
                  ImmutableList.of(
                      compilationInfo.getOutputGroups(), linkingInfo.getOutputGroups())));
      // On Windows, dynamic library is not built by default, so don't add them to filesToBuild.
      linkingInfo.addLinkingOutputsTo(
          filesBuilder, !featureConfiguration.isEnabled(CppRuleClasses.TARGETS_WINDOWS));
    }

    private boolean areSrcsBlacklisted() {
      return !new ProtoSourceFileBlacklist(
              ruleContext, getProtoToolchainProvider().blacklistedProtos())
          .checkSrcs(supportData.getDirectProtoSources(), "cc_proto_library");
    }

    private FeatureConfiguration getFeatureConfiguration(SupportData supportData) {
      ImmutableSet.Builder<String> requestedFeatures = new ImmutableSet.Builder<>();
      requestedFeatures.addAll(ruleContext.getFeatures());
      ImmutableSet.Builder<String> unsupportedFeatures = new ImmutableSet.Builder<>();
      unsupportedFeatures.addAll(ruleContext.getDisabledFeatures());
      unsupportedFeatures.add(CppRuleClasses.PARSE_HEADERS);
      unsupportedFeatures.add(CppRuleClasses.LAYERING_CHECK);
      if (!areSrcsBlacklisted() && supportData.hasProtoSources()) {
        requestedFeatures.add(CppRuleClasses.HEADER_MODULES);
      } else {
        unsupportedFeatures.add(CppRuleClasses.HEADER_MODULES);
      }
      FeatureConfiguration featureConfiguration =
          CcCommon.configureFeaturesOrReportRuleError(
              ruleContext,
              requestedFeatures.build(),
              unsupportedFeatures.build(),
              ccToolchain(ruleContext));
      return featureConfiguration;
    }

    private CcCompilationHelper initializeCompilationHelper(
        FeatureConfiguration featureConfiguration) {
      CcCompilationHelper helper =
          new CcCompilationHelper(
              ruleContext,
              cppSemantics,
              featureConfiguration,
              ccToolchain(ruleContext),
              CppHelper.getFdoSupportUsingDefaultCcToolchainAttribute(ruleContext));
      TransitiveInfoCollection runtime = getProtoToolchainProvider().runtime();
      if (runtime != null) {
        helper.addDeps(ImmutableList.of(runtime));
      }

      helper.addDeps(ruleContext.getPrerequisites("deps", TARGET));
      return helper;
    }

    private CcLinkingHelper initializeLinkingHelper(FeatureConfiguration featureConfiguration) {
      CcLinkingHelper helper =
          new CcLinkingHelper(
                  ruleContext,
                  cppSemantics,
                  featureConfiguration,
                  ccToolchain(ruleContext),
                  CppHelper.getFdoSupportUsingDefaultCcToolchainAttribute(ruleContext),
                  ruleContext.getConfiguration())
              .enableCcSpecificLinkParamsProvider()
              .enableCcNativeLibrariesProvider();
      TransitiveInfoCollection runtime = getProtoToolchainProvider().runtime();
      if (runtime != null) {
        helper.addDeps(ImmutableList.of(runtime));
      }
      helper.addDeps(ruleContext.getPrerequisites("deps", TARGET));
      // TODO(dougk): Configure output artifact with action_config
      // once proto compile action is configurable from the crosstool.
      if (!ccToolchain(ruleContext).supportsDynamicLinker()) {
        helper.setShouldCreateDynamicLibrary(false);
      }
      return helper;
    }

    private static CcToolchainProvider ccToolchain(RuleContext ruleContext) {
      return CppHelper.getToolchain(
          ruleContext,
          ruleContext.getPrerequisite(CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME, TARGET));
    }

    private ImmutableSet<Artifact> getOutputFiles(
        SupportData supportData, Iterable<String> suffixes) {
      ImmutableSet.Builder<Artifact> result = ImmutableSet.builder();
      for (String suffix : suffixes) {
        result.addAll(
            ProtoCommon.getGeneratedOutputs(
                ruleContext, supportData.getDirectProtoSources(), suffix));
      }
      return result.build();
    }

    private void registerBlacklistedSrcs(SupportData supportData, CcCompilationHelper helper) {
      // Hack: This is a proto_library for descriptor.proto or similar.
      //
      // The headers of those libraries are precomputed . They are also explicitly part of normal
      // cc_library rules that export them in their 'hdrs' attribute, and compile them as header
      // module if requested.
      //
      // The sole purpose of a proto_library with blacklisted srcs is so other proto_library rules
      // can import them from a protocol buffer, as proto_library rules can only depend on other
      // proto library rules.
      ImmutableList.Builder<PathFragment> headers = new ImmutableList.Builder<>();
      for (Artifact source : supportData.getDirectProtoSources()) {
        headers.add(FileSystemUtils.replaceExtension(source.getRootRelativePath(), ".pb.h"));
        headers.add(FileSystemUtils.replaceExtension(source.getRootRelativePath(), ".proto.h"));
      }
      // We add the header to the proto_library's module map as additional (textual) header for
      // two reasons:
      // 1. The header will be exported via a normal cc_library, and a header must only be exported
      //    non-textually from one library.
      // 2. We want to allow proto_library rules that depend on the bootstrap-hack proto_library
      //    to be layering-checked; we need to provide a module map for the layering check to work.
      helper.addAdditionalExportedHeaders(headers.build());
    }

    private void createProtoCompileAction(SupportData supportData, Collection<Artifact> outputs) {
      String genfilesPath =
          ruleContext
              .getConfiguration()
              .getGenfilesFragment()
              .getRelative(
                  ruleContext
                      .getLabel()
                      .getPackageIdentifier()
                      .getRepository()
                      .getPathUnderExecRoot())
              .getPathString();

      ImmutableList.Builder<ToolchainInvocation> invocations = ImmutableList.builder();
      invocations.add(
          new ToolchainInvocation("C++", checkNotNull(getProtoToolchainProvider()), genfilesPath));
      ProtoCompileActionBuilder.registerActions(
          ruleContext,
          invocations.build(),
          supportData.getDirectProtoSources(),
          supportData.getTransitiveImports(),
          supportData.getProtosInDirectDeps(),
          supportData.getTransitiveProtoPathFlags(),
          ruleContext.getLabel(),
          outputs,
          "C++",
          /* allowServices= */ true);
    }

    private ProtoLangToolchainProvider getProtoToolchainProvider() {
      return ruleContext.getPrerequisite(
          PROTO_TOOLCHAIN_ATTR, TARGET, ProtoLangToolchainProvider.class);
    }

    public void addProviders(ConfiguredAspect.Builder builder) {
      OutputGroupInfo outputGroupInfo = new OutputGroupInfo(outputGroups);
      builder.addProvider(
          new CcProtoLibraryProviders(
              filesBuilder.build(), ccLibraryProviders, outputGroupInfo));
      builder.addProviders(ccLibraryProviders);
      builder.addNativeDeclaredProvider(outputGroupInfo);
      if (headerProvider != null) {
        builder.addProvider(headerProvider);
      }
    }
  }
}
