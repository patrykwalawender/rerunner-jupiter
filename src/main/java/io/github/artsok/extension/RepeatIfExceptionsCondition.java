/*
 * (C) Copyright 2017 Artem Sokovets (http://github.com/artsok/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.github.artsok.extension;


import io.github.artsok.RepeatedIfExceptionsTest;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.StringUtils;
import org.opentest4j.TestAbortedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.toIntExact;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.StreamSupport.stream;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;
import static org.junit.platform.commons.util.AnnotationUtils.isAnnotated;


/**
 * Main condition for extension point @RepeatedIfExceptions
 * All logic in this class. See TestTemplateIterator where handler logic of repeat tests
 *
 * @author Artem Sokovets
 */
public class RepeatIfExceptionsCondition implements TestTemplateInvocationContextProvider, BeforeTestExecutionCallback,
        AfterTestExecutionCallback, TestExecutionExceptionHandler {

    private int totalRepeats = 0;
    private int minSuccess = 1;
    private List<Class<? extends Throwable>> repeatableExceptions;
    private boolean repeatableExceptionAppeared = false;
    private RepeatedIfExceptionsDisplayNameFormatter formatter;
    private List<Boolean> historyExceptionAppear;

    /**
     * Check that test method contain {@link RepeatedIfExceptionsTest} annotation
     *
     * @param extensionContext - encapsulates the context in which the current test or container is being executed
     * @return true/false
     */
    @Override
    public boolean supportsTestTemplate(ExtensionContext extensionContext) {
        return isAnnotated(extensionContext.getTestMethod(), RepeatedIfExceptionsTest.class);
    }


    /**
     * Context call TestTemplateInvocationContext
     *
     * @param extensionContext - Test Class Context
     * @return Stream of TestTemplateInvocationContext
     */
    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext extensionContext) {
        Preconditions.notNull(extensionContext.getTestMethod().orElse(null), "Test method must not be null");

        RepeatedIfExceptionsTest annotationParams = extensionContext.getTestMethod()
                .flatMap(testMethods -> findAnnotation(testMethods, RepeatedIfExceptionsTest.class))
                .orElseThrow(() -> new RepeatedIfException("The extension should not be executed "
                        + "unless the test method is annotated with @RepeatedIfExceptionsTest."));

        totalRepeats = annotationParams.repeats();
        minSuccess = annotationParams.minSuccess();
        historyExceptionAppear = Collections.synchronizedList(new ArrayList<>());
        Preconditions.condition(totalRepeats > 0, "Total repeats must be higher than 0");
        Preconditions.condition(minSuccess >= 1, "Total minimum success must be higher or equals than 1");

        String displayName = extensionContext.getDisplayName();



        formatter = displayNameFormatter(annotationParams, displayName);

        //Convert logic of repeated handler to spliterator
        Spliterator<TestTemplateInvocationContext> spliterator =
                spliteratorUnknownSize(new TestTemplateIterator(), Spliterator.NONNULL);
        return stream(spliterator, false);
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) throws Exception {
        repeatableExceptions = Stream.of(context.getTestMethod()
                .flatMap(testMethods -> findAnnotation(testMethods, RepeatedIfExceptionsTest.class))
                .orElseThrow(() -> new IllegalStateException("The extension should not be executed "))
                .exceptions()
        ).collect(Collectors.toList());
        repeatableExceptions.add(TestAbortedException.class);
    }

    /**
     * Check if exceptions that will appear in test same as we wait
     *
     * @param extensionContext - Test Class Context
     * @throws Exception - error if occurred
     */
    @Override
    public void afterTestExecution(ExtensionContext extensionContext) throws Exception {
        boolean exceptionAppeared = exceptionAppeared(extensionContext);
        historyExceptionAppear.add(exceptionAppeared);
    }

    private boolean exceptionAppeared(ExtensionContext extensionContext) {
        Class<? extends Throwable> exception = extensionContext.getExecutionException()
                .orElse(new RepeatedIfException("There is no exception in context")).getClass();
        return repeatableExceptions.stream()
                .anyMatch(ex -> ex.isAssignableFrom(exception) && !RepeatedIfException.class.isAssignableFrom(exception));
    }

    /**
     * Handler for display name
     *
     * @param test        - RepeatedIfExceptionsTest annotation
     * @param displayName - Name that will be represent to report
     * @return RepeatedIfExceptionsDisplayNameFormatter {@link RepeatedIfExceptionsDisplayNameFormatter}
     */
    private RepeatedIfExceptionsDisplayNameFormatter displayNameFormatter(RepeatedIfExceptionsTest test, String displayName) {
        String pattern = test.name().trim();
        if (StringUtils.isBlank(pattern)) {
            pattern = Optional.of(test.name())
                    .orElseThrow(() -> new RepeatedIfException("Exception occurred with name parameter of RepeatedIfExceptionsTest annotation"));
        }
        return new RepeatedIfExceptionsDisplayNameFormatter(pattern, displayName);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        if (appearedExceptionDoesNotAllowRepetitions(throwable)) {
            throw throwable;
        }
        repeatableExceptionAppeared = true;
        long currentSuccessCount = historyExceptionAppear.stream().filter(exceptionAppeared -> !exceptionAppeared).count();
        if (currentSuccessCount < minSuccess) {
            if (isMinSuccessTargetStillReachable(minSuccess)) {
                throw new TestAbortedException("Do not fail completely but repeat the test", throwable);
            } else {
                throw throwable;
            }
        }
    }

    private boolean appearedExceptionDoesNotAllowRepetitions(Throwable appearedException) {
        return repeatableExceptions.stream().noneMatch(ex -> ex.isAssignableFrom(appearedException.getClass()));
    }

    private boolean isMinSuccessTargetStillReachable(long minSuccessCount) {
        return historyExceptionAppear.stream().filter(bool -> bool).count() < totalRepeats - minSuccessCount;
    }

    /**
     * TestTemplateIterator (Repeat test if it failed)
     */
    class TestTemplateIterator implements Iterator<TestTemplateInvocationContext> {
        int currentIndex = 0;

        @Override
        public boolean hasNext() {
            if (currentIndex == 0) {
                return true;
            }
            return historyExceptionAppear.stream().anyMatch(ex -> ex) && currentIndex < totalRepeats;
        }

        @Override
        public TestTemplateInvocationContext next() {
            int successfulTestRepetitionsCount = toIntExact(historyExceptionAppear.stream().filter(b -> !b).count());
            if (hasNext()) {
                currentIndex++;
                return new RepeatedIfExceptionsInvocationContext(currentIndex, totalRepeats,
                        successfulTestRepetitionsCount, minSuccess, repeatableExceptionAppeared, formatter);
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

}
