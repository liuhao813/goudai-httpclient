package cloud.goudai.httpclient.processor;

import cloud.goudai.httpclient.common.GoudaiClient;
import cloud.goudai.httpclient.processor.internal.ClientProcessor;
import cloud.goudai.httpclient.processor.internal.Method;
import cloud.goudai.httpclient.processor.internal.Parameter;
import cloud.goudai.httpclient.processor.internal.SpringClientProcessor;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.ElementKindVisitor8;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author jianglin
 * @date 2018/11/21
 */
@SupportedAnnotationTypes({
        "cloud.goudai.httpclient.common.GoudaiClient"
})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class GoudaiClientProcessor extends AbstractProcessor {

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.typeUtils = processingEnv.getTypeUtils();
        this.elementUtils = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations,
                           final RoundEnvironment roundEnv) {
        messager.printMessage(Diagnostic.Kind.NOTE, "GoudaiClientProcessor process");
        Set<TypeElement> goudaiClients = getGoudaiClients(annotations, roundEnv);
        processGoudaiClients(goudaiClients);
        return false;
    }

    private void processGoudaiClients(final Set<TypeElement> goudaiClients) {
        String restTemplateName = "restTemplate";

        for (TypeElement typeElement : goudaiClients) {
            String packageName = elementUtils.getPackageOf(typeElement).getQualifiedName().toString();
            GoudaiClient goudaiClient = typeElement.getAnnotation(GoudaiClient.class);
            String name = goudaiClient.value();
            String datePattern = goudaiClient.datePattern();
            SpringClientProcessor processor = new SpringClientProcessor(restTemplateName, name, typeUtils, elementUtils, messager, datePattern);
            TypeSpec.Builder typeSpecBuilder = processor.processType(typeElement);

            for (Element element : typeElement.getEnclosedElements()) {
                if (element instanceof ExecutableElement) { // 方法
                    ExecutableElement methodElement = (ExecutableElement) element;
                    String methodName = methodElement.getSimpleName().toString();
                    Set<Modifier> modifiers = methodElement.getModifiers()
                            .stream()
                            .filter(modifier -> !Modifier.ABSTRACT.equals(modifier))
                            .collect(Collectors.toSet());

                    MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                            .addAnnotation(ClassName.get(Override.class))
                            .addModifiers(modifiers)
                            .returns(TypeName.get(methodElement.getReturnType()));

                    List<Parameter> parameters = new LinkedList<>();
                    int i = 0;
                    for (VariableElement parameter : methodElement.getParameters()) { // 参数
                        methodBuilder.addParameter(ParameterSpec.get(parameter));
                        parameters.add(i, Parameter.newBuilder()
                                .name(parameter.getSimpleName().toString())
                                .index(i)
                                .variableElement(parameter)
                                .typeUtils(this.typeUtils)
                                .elementUtils(this.elementUtils)
                                .messager(this.messager)
                                .build());
                        i++;
                    }
                    CodeBlock codeBlock = processor.processMethod(Method.newBuilder()
                            .typeUtils(this.typeUtils)
                            .elementUtils(this.elementUtils)
                            .messager(this.messager)
                            .name(methodName)
                            .element(methodElement)
                            .parameters(parameters)
                            .build());
                    methodBuilder.addCode(codeBlock);
                    typeSpecBuilder.addMethod(methodBuilder.build());
                }
            }
            processor.afterProcess(typeSpecBuilder);
            JavaFile javaFile = JavaFile.builder(packageName, typeSpecBuilder.build())
                    .indent("\t")
                    .build();
            try {
                javaFile.writeTo(filer);
            } catch (IOException e) {
                e.printStackTrace();
                messager.printMessage(Diagnostic.Kind.ERROR, "生成javaFile失败");
            }
        }

    }

    private Iterable<ClientProcessor> getProcessors() {

        Iterator<ClientProcessor> processorIterator = ServiceLoader.load(
                ClientProcessor.class,
                GoudaiClientProcessor.class.getClassLoader()
        )
                .iterator();
        List<ClientProcessor> processors = new ArrayList<>();

        while (processorIterator.hasNext()) {
            processors.add(processorIterator.next());
        }

        return processors;
    }

    private Set<TypeElement> getGoudaiClients(final Set<? extends TypeElement> annotations,
                                              final RoundEnvironment roundEnv) {
        Set<TypeElement> goudaiClients = new HashSet<>();

        for (TypeElement annotation : annotations) {
            //Indicates that the annotation's type isn't on the class path of the compiled
            //project. Let the compiler deal with that and print an appropriate error.
            if (annotation.getKind() != ElementKind.ANNOTATION_TYPE) {
                continue;
            }

            try {
                Set<? extends Element> annotatedMappers = roundEnv.getElementsAnnotatedWith(annotation);
                for (Element mapperElement : annotatedMappers) {
                    if (mapperElement.getKind() != ElementKind.INTERFACE) {
                        continue;
                    }
                    TypeElement mapperTypeElement = asTypeElement(mapperElement);

                    // on some JDKs, RoundEnvironment.getElementsAnnotatedWith( ... ) returns types with
                    // annotations unknown to the compiler, even though they are not declared Mappers
                    if (mapperTypeElement != null) {
                        goudaiClients.add(mapperTypeElement);
                    }
                }
            } catch (Throwable t) { // whenever that may happen, but just to stay on the save side
                handleUncaughtError(annotation, t);
                continue;
            }
        }
        return goudaiClients;
    }

    private TypeElement asTypeElement(Element element) {
        return element.accept(
                new ElementKindVisitor8<TypeElement, Void>() {
                    @Override
                    public TypeElement visitTypeAsInterface(TypeElement e, Void p) {
                        return e;
                    }

                    @Override
                    public TypeElement visitTypeAsClass(TypeElement e, Void p) {
                        return e;
                    }

                }, null
        );
    }

    private void handleUncaughtError(Element element, Throwable thrown) {
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));

        String reportableStacktrace = sw.toString().replace(System.getProperty("line.separator"), "  ");

        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR, "Internal error in the mapping processor: " + reportableStacktrace, element);
    }
}
