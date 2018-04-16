// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.skylark;

import static com.google.devtools.build.lib.analysis.BaseRuleClasses.RUN_UNDER;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.syntax.SkylarkType.castMap;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.INTEGER;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ActionsProvider;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.DefaultInfo;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.config.ConfigAwareRuleClassBuilder;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.skylark.SkylarkAttr.Descriptor;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.AttributeValueSource;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SkylarkImplicitOutputsFunctionWithCallback;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SkylarkImplicitOutputsFunctionWithMap;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageFactory.PackageContext;
import com.google.devtools.build.lib.packages.PredicateWithMessage;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleFactory;
import com.google.devtools.build.lib.packages.RuleFactory.BuildLangTypedAttributeValuesMap;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.packages.SkylarkAspect;
import com.google.devtools.build.lib.packages.SkylarkDefinedAspect;
import com.google.devtools.build.lib.packages.SkylarkExportable;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.ParamType;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkConstructor;
import com.google.devtools.build.lib.skylarkinterface.SkylarkGlobalLibrary;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkCallbackFunction;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.ConversionException;
import com.google.devtools.build.lib.util.Pair;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * A helper class to provide an easier API for Skylark rule definitions.
 */
@SkylarkGlobalLibrary
public class SkylarkRuleClassFunctions {

  // TODO(bazel-team): Copied from ConfiguredRuleClassProvider for the transition from built-in
  // rules to skylark extensions. Using the same instance would require a large refactoring.
  // If we don't want to support old built-in rules and Skylark simultaneously
  // (except for transition phase) it's probably OK.
  private static final LoadingCache<String, Label> labelCache =
      CacheBuilder.newBuilder().build(new CacheLoader<String, Label>() {
        @Override
        public Label load(String from) throws Exception {
          try {
            return Label.parseAbsolute(from, false);
          } catch (LabelSyntaxException e) {
            throw new Exception(from);
          }
        }
      });

  // TODO(bazel-team): Remove the code duplication (BaseRuleClasses and this class).
  /** Parent rule class for non-executable non-test Skylark rules. */
  public static final RuleClass baseRule =
      BaseRuleClasses.commonCoreAndSkylarkAttributes(
              BaseRuleClasses.nameAttribute(
                      new RuleClass.Builder("$base_rule", RuleClassType.ABSTRACT, true))
                  .add(attr("expect_failure", STRING)))
          .build();

  /** Parent rule class for executable non-test Skylark rules. */
  public static final RuleClass binaryBaseRule =
      new RuleClass.Builder("$binary_base_rule", RuleClassType.ABSTRACT, true, baseRule)
          .add(attr("args", STRING_LIST))
          .add(attr("output_licenses", LICENSE))
          .build();

  /** Parent rule class for test Skylark rules. */
  public static final RuleClass getTestBaseRule(String toolsRepository,
      PatchTransition lipoDataTransition) {
    return new RuleClass.Builder("$test_base_rule", RuleClassType.ABSTRACT, true, baseRule)
        .requiresConfigurationFragments(TestConfiguration.class)
        .add(
            attr("size", STRING)
                .value("medium")
                .taggable()
                .nonconfigurable("used in loading phase rule validation logic"))
        .add(
            attr("timeout", STRING)
                .taggable()
                .nonconfigurable("used in loading phase rule validation logic")
                .value(timeoutAttribute))
        .add(
            attr("flaky", BOOLEAN)
                .value(false)
                .taggable()
                .nonconfigurable("taggable - called in Rule.getRuleTags"))
        .add(attr("shard_count", INTEGER).value(-1))
        .add(
            attr("local", BOOLEAN)
                .value(false)
                .taggable()
                .nonconfigurable(
                    "policy decision: this should be consistent across configurations"))
        .add(attr("args", STRING_LIST))
        // Input files for every test action
        .add(
            attr("$test_runtime", LABEL_LIST)
                .cfg(HostTransition.INSTANCE)
                .value(
                    ImmutableList.of(
                        labelCache.getUnchecked(toolsRepository + "//tools/test:runtime"))))
        .add(
            attr("$test_setup_script", LABEL)
                .cfg(HostTransition.INSTANCE)
                .singleArtifact()
                .value(labelCache.getUnchecked(toolsRepository + "//tools/test:test_setup")))
        .add(
            attr("$collect_coverage_script", LABEL)
                .cfg(HostTransition.INSTANCE)
                .singleArtifact()
                .value(labelCache.getUnchecked(toolsRepository + "//tools/test:collect_coverage")))
        // Input files for test actions collecting code coverage
        .add(
            attr("$coverage_support", LABEL)
                .cfg(HostTransition.INSTANCE)
                .value(labelCache.getUnchecked("//tools/defaults:coverage_support")))
        // Used in the one-per-build coverage report generation action.
        .add(
            attr("$coverage_report_generator", LABEL)
                .cfg(HostTransition.INSTANCE)
                .value(labelCache.getUnchecked("//tools/defaults:coverage_report_generator"))
                .singleArtifact())
        .add(attr(":run_under", LABEL).cfg(lipoDataTransition).value(RUN_UNDER))
        .build();
  }

  @AutoCodec @AutoCodec.VisibleForSerialization
  static final Attribute.ComputedDefault timeoutAttribute =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          TestSize size = TestSize.getTestSize(rule.get("size", Type.STRING));
          if (size != null) {
            String timeout = size.getDefaultTimeout().toString();
            if (timeout != null) {
              return timeout;
            }
          }
          return "illegal";
        }
      };

  @SkylarkSignature(
    name = "struct",
    returnType = Info.class,
    doc =
        "Creates an immutable struct using the keyword arguments as attributes. It is used to "
            + "group multiple values together. Example:<br>"
            + "<pre class=\"language-python\">s = struct(x = 2, y = 3)\n"
            + "return s.x + getattr(s, \"y\")  # returns 5</pre>",
    extraKeywords = @Param(name = "kwargs", doc = "the struct attributes."),
    useLocation = true
  )
  private static final Provider struct = NativeProvider.STRUCT;

  @SkylarkSignature(
    name = "DefaultInfo",
    returnType = Provider.class,
    doc =
        "A provider that gives general information about a target's direct and transitive files. "
            + "Every rule type has this provider, even if it is not returned explicitly by the "
            + "rule's implementation function."
            + "<p>The <code>DefaultInfo</code> constructor accepts the following parameters:"
            + "<ul>"
            + "<li><code>executable</code>: If this rule is marked "
            + "<a href='globals.html#rule.executable'><code>executable</code></a> or "
            + "<a href='globals.html#rule.test'><code>test</code></a>, this is a "
            + "<a href='File.html'><code>File</code></a> object representing the file that should "
            + "be executed to run the target. By default it is the predeclared output "
            + "<code>ctx.outputs.executable</code>."
            + "<li><code>files</code>: A <a href='depset.html'><code>depset</code></a> of "
            + "<a href='File.html'><code>File</code></a> objects representing the default outputs "
            + "to build when this target is specified on the blaze command line. By default it is "
            + "all predeclared outputs."
            + "<li><code>runfiles</code>"
            + "<li><code>data_runfiles</code>"
            + "<li><code>default_runfiles</code>"
            + "</ul>"
            + "Each <code>DefaultInfo</code> instance has the following fields: "
            + "<ul>"
            + "<li><code>files</code>"
            + "<li><code>files_to_run</code>"
            + "<li><code>data_runfiles</code>"
            + "<li><code>default_runfiles</code>"
            + "</ul>"
            + "See the <a href='../rules.$DOC_EXT'>rules</a> page for more information."
  )
  private static final Provider defaultInfo = DefaultInfo.PROVIDER;

  @SkylarkSignature(
    name = "OutputGroupInfo",
    returnType = Provider.class,
    doc =
        "A provider that indicates what output groups a rule has.<br>"
            + "Instantiate this provider with <br>"
            + "<pre class=language-python>"
            + "OutputGroupInfo(group1 = &lt;files&gt;, group2 = &lt;files&gt;...)</pre>"
            + "See <a href=\"../rules.$DOC_EXT#requesting-output-files\">Requesting output files"
            + "</a> for more information."
  )
  private static final Provider outputGroupInfo = OutputGroupInfo.SKYLARK_CONSTRUCTOR;

  // TODO(bazel-team): Move to a "testing" namespace module. Normally we'd pass an objectType
  // to @SkylarkSignature to do this, but that doesn't work here because we're exposing an already-
  // configured BaseFunction, rather than defining a new BuiltinFunction. This should wait for
  // better support from the Skylark/Java interface, or perhaps support for first-class modules.
  @SkylarkSignature(
    name = "Actions",
    returnType = SkylarkProvider.class,
    doc =
        "<i>(Note: This is a provider type. Don't instantiate it yourself; use it to retrieve a "
            + "provider object from a <a href=\"Target.html\">Target</a>.)</i>"
            + "<br/><br/>"
            + "Provides access to the <a href=\"Action.html\">actions</a> generated by a rule. "
            + "There is one field, <code>by_file</code>, which is a dictionary from an output "
            + "of the rule to its corresponding generating action. "
            + "<br/><br/>"
            + "This is designed for testing rules, and should not be accessed outside "
            + "of test logic. This provider is only available for targets generated by rules"
            + " that have <a href=\"globals.html#rule._skylark_testable\">_skylark_testable</a> "
            + "set to <code>True</code>."
  )
  private static final Provider actions = ActionsProvider.SKYLARK_CONSTRUCTOR;

  @SkylarkSignature(
    name = "provider",
    returnType = Provider.class,
    doc =
        "Creates a declared provider 'constructor'. The return value of this "
            + "function can be used to create \"struct-like\" values. Example:<br>"
            + "<pre class=\"language-python\">data = provider()\n"
            + "d = data(x = 2, y = 3)\n"
            + "print(d.x + d.y) # prints 5</pre>",
    parameters = {
      @Param(
        name = "doc",
        type = String.class,
        defaultValue = "''",
        doc =
            "A description of the provider that can be extracted by documentation generating tools."
      ),
      @Param(
        name = "fields",
        doc = "If specified, restricts the set of allowed fields. <br>"
            + "Possible values are:"
            + "<ul>"
            + "  <li> list of fields:<br>"
            + "       <pre class=\"language-python\">provider(fields = ['a', 'b'])</pre><p>"
            + "  <li> dictionary field name -> documentation:<br>"
            + "       <pre class=\"language-python\">provider(\n"
            + "       fields = { 'a' : 'Documentation for a', 'b' : 'Documentation for b' })</pre>"
            + "</ul>"
            + "All fields are optional.",
        allowedTypes = {
            @ParamType(type = SkylarkList.class, generic1 = String.class),
            @ParamType(type = SkylarkDict.class)
        },
        noneable = true,
        named = true,
        positional = false,
        defaultValue = "None"
      )
    },
    useLocation = true
  )
  private static final BuiltinFunction provider =
      new BuiltinFunction("provider") {
        public Provider invoke(String doc, Object fields, Location location) throws EvalException {
          Iterable<String> fieldNames = null;
          if (fields instanceof SkylarkList<?>) {
            @SuppressWarnings("unchecked")
            SkylarkList<String> list = (SkylarkList<String>)
                    SkylarkType.cast(
                        fields,
                        SkylarkList.class, String.class, location,
                        "Expected list of strings or dictionary of string -> string for 'fields'");
            fieldNames = list;
          }  else  if (fields instanceof SkylarkDict) {
            Map<String, String> dict = SkylarkType.castMap(
                fields,
                String.class, String.class,
                "Expected list of strings or dictionary of string -> string for 'fields'");
            fieldNames = dict.keySet();
          }
          return SkylarkProvider.createUnexportedSchemaful(fieldNames, location);
        }
      };

  // TODO(bazel-team): implement attribute copy and other rule properties
  @SkylarkCallable(
    name = "rule",
    doc =
        "Creates a new rule, which can be called from a BUILD file or a macro to create targets."
            + "<p>Rules must be assigned to global variables in a .bzl file; the name of the "
            + "global variable is the rule's name."
            + "<p>Test rules are required to have a name ending in <code>_test</code>, while all "
            + "other rules must not have this suffix. (This restriction applies only to rules, not "
            + "to their targets.)",
    parameters = {
      @Param(
        name = "implementation",
        type = BaseFunction.class,
        legacyNamed = true,
        doc =
            "the function implementing this rule, must have exactly one parameter: "
                + "<a href=\"ctx.html\">ctx</a>. The function is called during the analysis "
                + "phase for each instance of the rule. It can access the attributes "
                + "provided by the user. It must create actions to generate all the declared "
                + "outputs."
      ),
      @Param(
        name = "test",
        type = Boolean.class,
        legacyNamed = true,
        defaultValue = "False",
        doc =
            "Whether this rule is a test rule, that is, whether it may be the subject of a "
                + "<code>blaze test</code> command. All test rules are automatically considered "
                + "<a href='#rule.executable'>executable</a>; it is unnecessary (and discouraged) "
                + "to explicitly set <code>executable = True</code> for a test rule. See the "
                + "<a href='../rules.$DOC_EXT#executable-rules-and-test-rules'>Rules page</a> for "
                + "more information."
      ),
      @Param(
        name = "attrs",
        type = SkylarkDict.class,
        legacyNamed = true,
        noneable = true,
        defaultValue = "None",
        doc =
            "dictionary to declare all the attributes of the rule. It maps from an attribute "
                + "name to an attribute object (see <a href=\"attr.html\">attr</a> module). "
                + "Attributes starting with <code>_</code> are private, and can be used to "
                + "add an implicit dependency on a label. The attribute <code>name</code> is "
                + "implicitly added and must not be specified. Attributes "
                + "<code>visibility</code>, <code>deprecation</code>, <code>tags</code>, "
                + "<code>testonly</code>, and <code>features</code> are implicitly added and "
                + "cannot be overridden."
      ),
      // TODO(bazel-team): need to give the types of these builtin attributes
      @Param(
        name = "outputs",
        type = SkylarkDict.class,
        legacyNamed = true,
        callbackEnabled = true,
        noneable = true,
        defaultValue = "None",
        doc =
            "<b>Experimental:</b> This API is in the process of being redesigned."
                + "<p>A schema for defining predeclared outputs. Unlike <a href='attr.html#output'>"
                + "<code>output</code></a> and <a href='attr.html#output_list'><code>output_list"
                + "</code></a> attributes, the user does not specify the labels for these files. "
                + "See the <a href='../rules.$DOC_EXT#files'>Rules page</a> for more on "
                + "predeclared outputs."
                + "<p>The value of this argument is either a dictionary or a callback function "
                + "that produces a dictionary. The callback works similar to computed dependency "
                + "attributes: The function's parameter names are matched against the rule's "
                + "attributes, so for example if you pass <code>outputs = _my_func</code> with the "
                + "definition <code>def _my_func(srcs, deps): ...</code>, the function has access "
                + "to the attributes <code>srcs</code> and <code>deps</code>. Whether the "
                + "dictionary is specified directly or via a function, it is interpreted as "
                + "follows."
                + "<p>Each entry in the dictionary creates a predeclared output where the key is "
                + "an identifier and the value is a string template that determines the output's "
                + "label. In the rule's implementation function, the identifier becomes the field "
                + "name used to access the output's <a href='File.html'><code>File</code></a> in "
                + "<a href='ctx.html#outputs'><code>ctx.outputs</code></a>. The output's label has "
                + "the same package as the rule, and the part after the package is produced by "
                + "substituting each placeholder of the form <code>\"%{ATTR}\"</code> with a "
                + "string formed from the value of the attribute <code>ATTR</code>:"
                + "<ul>"
                + "<li>String-typed attributes are substituted verbatim."
                + "<li>Label-typed attributes become the part of the label after the package, "
                + "minus the file extension. For example, the label <code>\"//pkg:a/b.c\"</code> "
                + "becomes <code>\"a/b\"</code>."
                + "<li>Output-typed attributes become the part of the label after the package, "
                + "including the file extension (for the above example, <code>\"a/b.c\"</code>)."
                + "<li>All list-typed attributes (for example, <code>attr.label_list</code>) used "
                + "in placeholders are required to have <i>exactly one element</i>. Their "
                + "conversion is the same as their non-list version (<code>attr.label</code>)."
                + "<li>Other attribute types may not appear in placeholders."
                + "<li>The special non-attribute placeholders <code>%{dirname}</code> and <code>"
                + "%{basename}</code> expand to those parts of the rule's label, excluding its "
                + "package. For example, in <code>\"//pkg:a/b.c\"</code>, the dirname is <code>"
                + "a</code> and the basename is <code>b.c</code>."
                + "</ul>"
                + "<p>In practice, the most common substitution placeholder is "
                + "<code>\"%{name}\"</code>. For example, for a target named \"foo\", the outputs "
                + "dict <code>{\"bin\": \"%{name}.exe\"}</code> predeclares an output named "
                + "<code>foo.exe</code> that is accessible in the implementation function as "
                + "<code>ctx.outputs.bin</code>."
      ),
      @Param(
        name = "executable",
        type = Boolean.class,
        legacyNamed = true,
        defaultValue = "False",
        doc =
            "Whether this rule is considered executable, that is, whether it may be the subject of "
                + "a <code>blaze run</code> command. See the "
                + "<a href='../rules.$DOC_EXT#executable-rules-and-test-rules'>Rules page</a> for "
                + "more information."
      ),
      @Param(
        name = "output_to_genfiles",
        type = Boolean.class,
        legacyNamed = true,
        defaultValue = "False",
        doc =
            "If true, the files will be generated in the genfiles directory instead of the "
                + "bin directory. Unless you need it for compatibility with existing rules "
                + "(e.g. when generating header files for C++), do not set this flag."
      ),
      @Param(
        name = "fragments",
        type = SkylarkList.class,
        legacyNamed = true,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "List of names of configuration fragments that the rule requires "
                + "in target configuration."
      ),
      @Param(
        name = "host_fragments",
        type = SkylarkList.class,
        legacyNamed = true,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "List of names of configuration fragments that the rule requires "
                + "in host configuration."
      ),
      @Param(
        name = "_skylark_testable",
        type = Boolean.class,
        legacyNamed = true,
        defaultValue = "False",
        doc =
            "<i>(Experimental)</i><br/><br/>"
                + "If true, this rule will expose its actions for inspection by rules that "
                + "depend on it via an <a href=\"globals.html#Actions\">Actions</a> "
                + "provider. The provider is also available to the rule itself by calling "
                + "<a href=\"ctx.html#created_actions\">ctx.created_actions()</a>."
                + "<br/><br/>"
                + "This should only be used for testing the analysis-time behavior of "
                + "Skylark rules. This flag may be removed in the future."
      ),
      @Param(
        name = "toolchains",
        type = SkylarkList.class,
        legacyNamed = true,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "<i>(Experimental)</i><br/><br/>"
                + "If set, the set of toolchains this rule requires. Toolchains will be "
                + "found by checking the current platform, and provided to the rule "
                + "implementation via <code>ctx.toolchain</code>."
      ),
      @Param(
        name = "doc",
        type = String.class,
        legacyNamed = true,
        defaultValue = "''",
        doc = "A description of the rule that can be extracted by documentation generating tools."
      )
    },
    useAst = true,
    useEnvironment = true
  )
  @SuppressWarnings({"rawtypes", "unchecked"}) // castMap produces
  // an Attribute.Builder instead of a Attribute.Builder<?> but it's OK.
  public BaseFunction rule(
      BaseFunction implementation,
      Boolean test,
      Object attrs,
      Object implicitOutputs,
      Boolean executable,
      Boolean outputToGenfiles,
      SkylarkList fragments,
      SkylarkList hostFragments,
      Boolean skylarkTestable,
      SkylarkList<String> toolchains,
      String doc,
      FuncallExpression ast,
      Environment funcallEnv)
      throws EvalException, ConversionException {
    funcallEnv.checkLoadingOrWorkspacePhase("rule", ast.getLocation());
    RuleClassType type = test ? RuleClassType.TEST : RuleClassType.NORMAL;
    RuleClass parent =
        test
            ? getTestBaseRule(
                SkylarkUtils.getToolsRepository(funcallEnv),
                (PatchTransition) SkylarkUtils.getLipoDataTransition(funcallEnv))
            : (executable ? binaryBaseRule : baseRule);

    // We'll set the name later, pass the empty string for now.
    RuleClass.Builder builder = new RuleClass.Builder("", type, true, parent);
    ImmutableList<Pair<String, SkylarkAttr.Descriptor>> attributes =
        attrObjectToAttributesList(attrs, ast);

    if (skylarkTestable) {
      builder.setSkylarkTestable();
    }

    if (executable || test) {
      addAttribute(
          ast.getLocation(),
          builder,
          attr("$is_executable", BOOLEAN)
              .value(true)
              .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
              .build());
      builder.setExecutableSkylark();
    }

    if (implicitOutputs != Runtime.NONE) {
      if (implicitOutputs instanceof BaseFunction) {
        BaseFunction func = (BaseFunction) implicitOutputs;
        SkylarkCallbackFunction callback =
            new SkylarkCallbackFunction(func, ast, funcallEnv.getSemantics());
        builder.setImplicitOutputsFunction(
            new SkylarkImplicitOutputsFunctionWithCallback(callback, ast.getLocation()));
      } else {
        builder.setImplicitOutputsFunction(
            new SkylarkImplicitOutputsFunctionWithMap(
                ImmutableMap.copyOf(
                    castMap(
                        implicitOutputs,
                        String.class,
                        String.class,
                        "implicit outputs of the rule class"))));
      }
    }

    if (outputToGenfiles) {
      builder.setOutputToGenfiles();
    }

    builder.requiresConfigurationFragmentsBySkylarkModuleName(
        fragments.getContents(String.class, "fragments"));
    ConfigAwareRuleClassBuilder.of(builder)
        .requiresHostConfigurationFragmentsBySkylarkModuleName(
            hostFragments.getContents(String.class, "host_fragments"));
    builder.setConfiguredTargetFunction(implementation);
    builder.setRuleDefinitionEnvironmentLabelAndHashCode(
        funcallEnv.getGlobals().getTransitiveLabel(),
        funcallEnv.getTransitiveContentHashCode());
    builder.addRequiredToolchains(collectToolchainLabels(toolchains, ast));

    return new SkylarkRuleFunction(builder, type, attributes, ast.getLocation());
  }

  protected static ImmutableList<Pair<String, Descriptor>> attrObjectToAttributesList(
      Object attrs, FuncallExpression ast) throws EvalException {
    ImmutableList.Builder<Pair<String, Descriptor>> attributes = ImmutableList.builder();

    if (attrs != Runtime.NONE) {
      for (Map.Entry<String, Descriptor> attr :
          castMap(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
        Descriptor attrDescriptor = attr.getValue();
        AttributeValueSource source = attrDescriptor.getValueSource();
        String attrName = source.convertToNativeName(attr.getKey(), ast.getLocation());
        attributes.add(Pair.of(attrName, attrDescriptor));
      }
    }
    return attributes.build();
  }

  private static void addAttribute(
      Location location, RuleClass.Builder builder, Attribute attribute) throws EvalException {
    try {
      builder.addOrOverrideAttribute(attribute);
    } catch (IllegalArgumentException ex) {
      throw new EvalException(location, ex);
    }
  }

  private static ImmutableList<Label> collectToolchainLabels(
      Iterable<String> rawLabels, FuncallExpression ast) throws EvalException {
    ImmutableList.Builder<Label> requiredToolchains = new ImmutableList.Builder<>();
    for (String rawLabel : rawLabels) {
      try {
        Label toolchainLabel = Label.parseAbsolute(rawLabel);
        requiredToolchains.add(toolchainLabel);
      } catch (LabelSyntaxException e) {
        throw new EvalException(
            ast.getLocation(),
            String.format("Unable to parse toolchain %s: %s", rawLabel, e.getMessage()),
            e);
      }
    }

    return requiredToolchains.build();
  }

  @SkylarkSignature(
    name = "aspect",
    doc =
        "Creates a new aspect. The result of this function must be stored in a global value. "
            + "Please see the <a href=\"../aspects.md\">introduction to Aspects</a> for more "
            + "details.",
    returnType = SkylarkAspect.class,
    parameters = {
      @Param(
        name = "implementation",
        type = BaseFunction.class,
        doc =
            "the function implementing this aspect. Must have two parameters: "
                + "<a href=\"Target.html\">Target</a> (the target to which the aspect is "
                + "applied) and <a href=\"ctx.html\">ctx</a>. Attributes of the target are "
                + "available via ctx.rule field. The function is called during the analysis "
                + "phase for each application of an aspect to a target."
      ),
      @Param(
        name = "attr_aspects",
        type = SkylarkList.class,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "List of attribute names.  The aspect propagates along dependencies specified "
                + "by attributes of a target with this name. The list can also contain a single "
                + "string '*': in that case aspect propagates along all dependencies of a target."
      ),
      @Param(
        name = "attrs",
        type = SkylarkDict.class,
        noneable = true,
        defaultValue = "None",
        doc =
            "dictionary to declare all the attributes of the aspect.  "
                + "It maps from an attribute name to an attribute object "
                + "(see <a href=\"attr.html\">attr</a> module). "
                + "Aspect attributes are available to implementation function as fields of ctx "
                + "parameter. Implicit attributes starting with <code>_</code> must have default "
                + "values, and have type <code>label</code> or <code>label_list</code>. "
                + "Explicit attributes must have type <code>string</code>, and must use the "
                + "<code>values</code> restriction. If explicit attributes are present, the "
                + "aspect can only be used with rules that have attributes of the same name and "
                + "type, with valid values."
      ),
      @Param(
        name = "required_aspect_providers",
        type = SkylarkList.class,
        defaultValue = "[]",
        doc =
            "Allow the aspect to inspect other aspects. If the aspect propagates along "
                + "a dependency, and the underlying rule sends a different aspect along that "
                + "dependency, and that aspect provides one of the providers listed here, this "
                + "aspect will see the providers provided by that aspect. "
                + "<p>The value should be either a list of providers, or a "
                + "list of lists of providers. This aspect will 'see'  the underlying aspects that "
                + "provide  ALL providers from at least ONE of these lists. A single list of "
                + "providers will be automatically converted to a list containing one list of "
                + "providers."
      ),
      @Param(
        name = "provides",
        type = SkylarkList.class,
        defaultValue = "[]",
        doc =
            "A list of providers this aspect is guaranteed to provide. "
                + "It is an error if a provider is listed here and the aspect "
                + "implementation function does not return it."
      ),
      @Param(
        name = "fragments",
        type = SkylarkList.class,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "List of names of configuration fragments that the aspect requires "
                + "in target configuration."
      ),
      @Param(
        name = "host_fragments",
        type = SkylarkList.class,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "List of names of configuration fragments that the aspect requires "
                + "in host configuration."
      ),
      @Param(
        name = "toolchains",
        type = SkylarkList.class,
        generic1 = String.class,
        defaultValue = "[]",
        doc =
            "<i>(Experimental)</i><br/><br/>"
                + "If set, the set of toolchains this rule requires. Toolchains will be "
                + "found by checking the current platform, and provided to the rule "
                + "implementation via <code>ctx.toolchain</code>."
      ),
      @Param(
        name = "doc",
        type = String.class,
        defaultValue = "''",
        doc = "A description of the aspect that can be extracted by documentation generating tools."
      )
    },
    useEnvironment = true,
    useAst = true
  )
  private static final BuiltinFunction aspect =
      new BuiltinFunction("aspect") {
        public SkylarkAspect invoke(
            BaseFunction implementation,
            SkylarkList attributeAspects,
            Object attrs,
            SkylarkList requiredAspectProvidersArg,
            SkylarkList providesArg,
            SkylarkList fragments,
            SkylarkList hostFragments,
            SkylarkList<String> toolchains,
            String doc,
            FuncallExpression ast,
            Environment funcallEnv)
            throws EvalException {
          ImmutableList.Builder<String> attrAspects = ImmutableList.builder();
          for (Object attributeAspect : attributeAspects) {
            String attrName = STRING.convert(attributeAspect, "attr_aspects");

            if (attrName.equals("*") && attributeAspects.size() != 1) {
              throw new EvalException(
                  ast.getLocation(), "'*' must be the only string in 'attr_aspects' list");
            }

            if (!attrName.startsWith("_")) {
              attrAspects.add(attrName);
            } else {
              // Implicit attribute names mean either implicit or late-bound attributes
              // (``$attr`` or ``:attr``). Depend on both.
              attrAspects.add(
                  AttributeValueSource.COMPUTED_DEFAULT.convertToNativeName(attrName, location));
              attrAspects.add(
                  AttributeValueSource.LATE_BOUND.convertToNativeName(attrName, location));
            }
          }

          ImmutableList<Pair<String, SkylarkAttr.Descriptor>> descriptors =
              attrObjectToAttributesList(attrs, ast);
          ImmutableList.Builder<Attribute> attributes = ImmutableList.builder();
          ImmutableSet.Builder<String> requiredParams = ImmutableSet.builder();
          for (Pair<String, Descriptor> nameDescriptorPair : descriptors) {
            String nativeName = nameDescriptorPair.first;
            boolean hasDefault = nameDescriptorPair.second.hasDefault();
            Attribute attribute = nameDescriptorPair.second.build(nameDescriptorPair.first);
            if (attribute.getType() == Type.STRING
                && ((String) attribute.getDefaultValue(null)).isEmpty()) {
              hasDefault = false; // isValueSet() is always true for attr.string.
            }
            if (!Attribute.isImplicit(nativeName) && !Attribute.isLateBound(nativeName)) {
              if (!attribute.checkAllowedValues() || attribute.getType() != Type.STRING) {
                throw new EvalException(
                    ast.getLocation(),
                    String.format(
                        "Aspect parameter attribute '%s' must have type 'string' and use the "
                            + "'values' restriction.",
                        nativeName));
              }
              if (!hasDefault) {
                requiredParams.add(nativeName);
              } else {
                PredicateWithMessage<Object> allowed = attribute.getAllowedValues();
                Object defaultVal = attribute.getDefaultValue(null);
                if (!allowed.apply(defaultVal)) {
                  throw new EvalException(
                      ast.getLocation(),
                      String.format(
                          "Aspect parameter attribute '%s' has a bad default value: %s",
                          nativeName, allowed.getErrorReason(defaultVal)));
                }
              }
            } else if (!hasDefault) { // Implicit or late bound attribute
              String skylarkName = "_" + nativeName.substring(1);
              throw new EvalException(
                  ast.getLocation(),
                  String.format("Aspect attribute '%s' has no default value.", skylarkName));
            }
            attributes.add(attribute);
          }

          for (Object o : providesArg) {
            if (!SkylarkAttr.isProvider(o)) {
              throw new EvalException(
                  ast.getLocation(),
                  String.format(
                      "Illegal argument: element in 'provides' is of unexpected type. "
                          + "Should be list of providers, but got %s. ",
                      EvalUtils.getDataTypeName(o, true)));
            }
          }
          return new SkylarkDefinedAspect(
              implementation,
              attrAspects.build(),
              attributes.build(),
              SkylarkAttr.buildProviderPredicate(
                  requiredAspectProvidersArg, "required_aspect_providers", ast.getLocation()),
              SkylarkAttr.getSkylarkProviderIdentifiers(providesArg, ast.getLocation()),
              requiredParams.build(),
              ImmutableSet.copyOf(fragments.getContents(String.class, "fragments")),
              HostTransition.INSTANCE,
              ImmutableSet.copyOf(hostFragments.getContents(String.class, "host_fragments")),
              collectToolchainLabels(toolchains, ast));
        }
      };

  /**
   * The implementation for the magic function "rule" that creates Skylark rule classes.
   *
   * <p>Exactly one of {@link #builder} or {@link #ruleClass} is null except inside {@link #export}.
   */
  public static final class SkylarkRuleFunction extends BaseFunction
      implements SkylarkExportable, RuleFunction {
    private RuleClass.Builder builder;

    private RuleClass ruleClass;
    private final RuleClassType type;
    private ImmutableList<Pair<String, SkylarkAttr.Descriptor>> attributes;
    private final Location definitionLocation;
    private Label skylarkLabel;

    public SkylarkRuleFunction(
        Builder builder,
        RuleClassType type,
        ImmutableList<Pair<String, SkylarkAttr.Descriptor>> attributes,
        Location definitionLocation) {
      super("rule", FunctionSignature.KWARGS);
      this.builder = builder;
      this.type = type;
      this.attributes = attributes;
      this.definitionLocation = definitionLocation;
    }

    /** This is for post-export reconstruction for serialization. */
    private SkylarkRuleFunction(
        RuleClass ruleClass, RuleClassType type, Location definitionLocation, Label skylarkLabel) {
      super("rule", FunctionSignature.KWARGS);
      Preconditions.checkNotNull(
          ruleClass,
          "RuleClass must be non-null as this SkylarkRuleFunction should have been exported.");
      Preconditions.checkNotNull(
          skylarkLabel,
          "Label must be non-null as this SkylarkRuleFunction should have been exported.");
      this.ruleClass = ruleClass;
      this.type = type;
      this.definitionLocation = definitionLocation;
      this.skylarkLabel = skylarkLabel;
    }

    @Override
    @SuppressWarnings("unchecked") // the magic hidden $pkg_context variable is guaranteed
    // to be a PackageContext
    public Object call(Object[] args, FuncallExpression ast, Environment env)
        throws EvalException, InterruptedException, ConversionException {
      env.checkLoadingPhase(getName(), ast.getLocation());
      if (ruleClass == null) {
        throw new EvalException(ast.getLocation(),
            "Invalid rule class hasn't been exported by a Skylark file");
      }

      for (Attribute attribute : ruleClass.getAttributes()) {
        // TODO(dslomov): If a Skylark parameter extractor is specified for this aspect, its
        // attributes may not be required.
        for (Map.Entry<String, ImmutableSet<String>> attrRequirements :
            attribute.getRequiredAspectParameters().entrySet()) {
          for (String required : attrRequirements.getValue()) {
            if (!ruleClass.hasAttr(required, Type.STRING)) {
              throw new EvalException(definitionLocation, String.format(
                  "Aspect %s requires rule %s to specify attribute '%s' with type string.",
                  attrRequirements.getKey(),
                  ruleClass.getName(),
                  required));
            }
          }
        }
      }

      BuildLangTypedAttributeValuesMap attributeValues =
          new BuildLangTypedAttributeValuesMap((Map<String, Object>) args[0]);
      try {
        PackageContext pkgContext = (PackageContext) env.lookup(PackageFactory.PKG_CONTEXT);
        if (pkgContext == null) {
          throw new EvalException(ast.getLocation(),
              "Cannot instantiate a rule when loading a .bzl file. Rules can only be called from "
                  + "a BUILD file (possibly via a macro).");
        }
        RuleFactory.createAndAddRule(
            pkgContext,
            ruleClass,
            attributeValues,
            ast,
            env,
            pkgContext.getAttributeContainerFactory().apply(ruleClass));
        return Runtime.NONE;
      } catch (InvalidRuleException | NameConflictException e) {
        throw new EvalException(ast.getLocation(), e.getMessage());
      }
    }

    /**
     * Export a RuleFunction from a Skylark file with a given name.
     */
    public void export(Label skylarkLabel, String ruleClassName) throws EvalException {
      Preconditions.checkState(ruleClass == null && builder != null);
      this.skylarkLabel = skylarkLabel;
      if (type == RuleClassType.TEST != TargetUtils.isTestRuleName(ruleClassName)) {
        throw new EvalException(definitionLocation, "Invalid rule class name '" + ruleClassName
            + "', test rule class names must end with '_test' and other rule classes must not");
      }
      for (Pair<String, SkylarkAttr.Descriptor> attribute : attributes) {
        SkylarkAttr.Descriptor descriptor = attribute.getSecond();

        addAttribute(definitionLocation, builder,
            descriptor.build(attribute.getFirst()));
      }
      this.ruleClass = builder.build(ruleClassName, skylarkLabel + "%" + ruleClassName);

      this.builder = null;
      this.attributes = null;
    }

    public RuleClass getRuleClass() {
      Preconditions.checkState(ruleClass != null && builder == null);
      return ruleClass;
    }

    @Override
    public boolean isExported() {
      return skylarkLabel != null;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<rule>");
    }
  }

  /**
   * All classes of values that need special processing after they are exported from an extension
   * file.
   *
   * <p>Order in list is significant: all {@link SkylarkDefinedAspect}s need to be exported before
   * {@link SkylarkRuleFunction}s etc.
   */
  private static final ImmutableList<Class<? extends SkylarkExportable>> EXPORTABLES =
      ImmutableList.of(
          SkylarkProvider.class, SkylarkDefinedAspect.class, SkylarkRuleFunction.class);

  @SkylarkCallable(
    name = "Label",
    doc =
        "Creates a Label referring to a BUILD target. Use "
            + "this function only when you want to give a default value for the label attributes. "
            + "The argument must refer to an absolute label. "
            + "Example: <br><pre class=language-python>Label(\"//tools:default\")</pre>",
    parameters = {
      @Param(name = "label_string", type = String.class, legacyNamed = true,
          doc = "the label string."),
      @Param(
        name = "relative_to_caller_repository",
        type = Boolean.class,
        defaultValue = "False",
        named = true,
        positional = false,
        doc =
            "Deprecated. Do not use. "
                + "When relative_to_caller_repository is True and the calling thread is a rule's "
                + "implementation function, then a repo-relative label //foo:bar is resolved "
                + "relative to the rule's repository.  For calls to Label from any other "
                + "thread, or calls in which the relative_to_caller_repository flag is False, "
                + "a repo-relative label is resolved relative to the file in which the "
                + "Label() call appears."
      )
    },
    useLocation = true,
    useEnvironment = true
  )
  @SkylarkConstructor(objectType = Label.class)
  public Label label(
      String labelString, Boolean relativeToCallerRepository, Location loc, Environment env)
      throws EvalException {
    Label parentLabel = null;
    if (relativeToCallerRepository) {
      parentLabel = env.getCallerLabel();
    } else {
      parentLabel = env.getGlobals().getTransitiveLabel();
    }
    try {
      if (parentLabel != null) {
        LabelValidator.parseAbsoluteLabel(labelString);
        labelString = parentLabel.getRelative(labelString).getUnambiguousCanonicalForm();
      }
      return labelCache.get(labelString);
    } catch (LabelValidator.BadLabelException | LabelSyntaxException | ExecutionException e) {
      throw new EvalException(loc, "Illegal absolute label syntax: " + labelString);
    }
  }

  @SkylarkSignature(
    name = "FileType",
    doc =
        "Deprecated. Creates a file filter from a list of strings. For example, to match "
            + "files ending with .cc or .cpp, use: "
            + "<pre class=language-python>FileType([\".cc\", \".cpp\"])</pre>",
    returnType = SkylarkFileType.class,
    objectType = SkylarkFileType.class,
    parameters = {
      @Param(
        name = "types",
        type = SkylarkList.class,
        generic1 = String.class,
        defaultValue = "[]",
        doc = "a list of the accepted file extensions."
      )
    },
    useLocation = true,
    useEnvironment = true
  )
  private static final BuiltinFunction fileType =
      new BuiltinFunction("FileType") {
        public SkylarkFileType invoke(SkylarkList types, Location loc, Environment env)
            throws EvalException {
          if (env.getSemantics().incompatibleDisallowFileType()) {
            throw new EvalException(
                loc,
                "FileType function is not available. You may use a list of strings instead. "
                    + "You can temporarily reenable the function by passing the flag "
                    + "--incompatible_disallow_filetype=false");
          }
          return SkylarkFileType.of(types.getContents(String.class, "types"));
        }
      };

  // We want the FileType ctor to show up under the FileType documentation, but to be a "global
  // function." Thus, we create a global FileType object here, which just points to the Skylark
  // function above.
  @SkylarkSignature(name = "FileType",
      documented = false)
  private static final BuiltinFunction globalFileType = fileType;

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(SkylarkRuleClassFunctions.class);
  }
}
