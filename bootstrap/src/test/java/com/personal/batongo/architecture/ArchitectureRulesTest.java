package com.personal.batongo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(
        packages = "com.personal.batongo",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRulesTest {

    @ArchTest
    void domainDoesNotDependOnOuterLayers(JavaClasses classes) {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..adapter..", "..bootstrap..")
                .as("도메인은 애플리케이션, 어댑터, 부트스트랩에 의존하지 않는다")
                .check(classes);
    }

    @ArchTest
    void applicationDoesNotDependOnAdaptersOrBootstrap(JavaClasses classes) {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..bootstrap..")
                .as("애플리케이션은 어댑터 구현체와 부트스트랩에 의존하지 않는다")
                .check(classes);
    }

    @ArchTest
    void restControllersDoNotDependOnOutputPorts(JavaClasses classes) {
        noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat()
                .resideInAPackage("..application.port.out..")
                .as("REST 컨트롤러는 출력 포트에 직접 의존하지 않는다")
                .check(classes);
    }

    @ArchTest
    void inboundAdaptersDoNotDependOnOutboundAdapters(JavaClasses classes) {
        noClasses()
                .that().resideInAPackage("..adapter.in..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .as("인바운드 어댑터는 아웃바운드 어댑터 구현체에 의존하지 않는다")
                .check(classes);
    }

    @ArchTest
    void outboundAdaptersDoNotDependOnInboundAdapters(JavaClasses classes) {
        noClasses()
                .that().resideInAPackage("..adapter.out..")
                .should().dependOnClassesThat()
                .resideInAPackage("..adapter.in..")
                .as("아웃바운드 어댑터는 인바운드 어댑터 구현체에 의존하지 않는다")
                .check(classes);
    }
}
