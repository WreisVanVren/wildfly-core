/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.moduleservice;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.jboss.as.server.logging.ServerLogger;
import org.jboss.as.server.deployment.module.ModuleDependency;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.inject.InjectionException;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * Module phase resolve service. Basically this service attempts to resolve
 * every dynamic transitive dependency of a module, and allows the module resolved service
 * to start once this is complete.
 *
 * @author Stuart Douglas
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class ModuleResolvePhaseService implements Service<ModuleResolvePhaseService> {

    public static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("module", "resolve", "phase");

    private final ModuleIdentifier moduleIdentifier;

    private final Set<ModuleIdentifier> alreadyResolvedModules;

    private final int phaseNumber;

    /**
     * module specification that were resolved this phase. These are injected as the relevant spec services start.
     */
    private final Set<ModuleDefinition> moduleSpecs = Collections.synchronizedSet(new HashSet<ModuleDefinition>());

    public ModuleResolvePhaseService(final ModuleIdentifier moduleIdentifier, final Set<ModuleIdentifier> alreadyResolvedModules, final int phaseNumber) {
        this.moduleIdentifier = moduleIdentifier;
        this.alreadyResolvedModules = alreadyResolvedModules;
        this.phaseNumber = phaseNumber;
    }

    public ModuleResolvePhaseService(final ModuleIdentifier moduleIdentifier) {
        this.moduleIdentifier = moduleIdentifier;
        this.alreadyResolvedModules = Collections.emptySet();
        this.phaseNumber = 0;
    }

    @Override
    public void start(final StartContext startContext) throws StartException {
        Set<ModuleDependency> nextPhaseIdentifiers = new HashSet<>();
        final Set<ModuleIdentifier> nextAlreadySeen = new HashSet<>(alreadyResolvedModules);
        for (final ModuleDefinition spec : moduleSpecs) {
            if (spec != null) { //this can happen for optional dependencies
                for (ModuleDependency dep : spec.getDependencies()) {
                    if (dep.isOptional()) continue; // we don't care about optional dependencies
                    if (ServiceModuleLoader.isDynamicModule(dep.getIdentifier())) {
                        if (!alreadyResolvedModules.contains(dep.getIdentifier())) {
                            nextAlreadySeen.add(dep.getIdentifier());
                            nextPhaseIdentifiers.add(dep);
                        }
                    }
                }
            }
        }
        if (nextPhaseIdentifiers.isEmpty()) {
            ServiceModuleLoader.installModuleResolvedService(startContext.getChildTarget(), moduleIdentifier);
        } else {
            installService(startContext.getChildTarget(), moduleIdentifier, phaseNumber + 1, nextPhaseIdentifiers, nextAlreadySeen);
        }
    }

    public static void installService(final ServiceTarget serviceTarget, final ModuleDefinition moduleDefinition) {
        final ModuleResolvePhaseService nextPhaseService = new ModuleResolvePhaseService(moduleDefinition.getModuleIdentifier(), Collections.singleton(moduleDefinition.getModuleIdentifier()), 0);
        nextPhaseService.getModuleSpecs().add(moduleDefinition);
        ServiceBuilder<ModuleResolvePhaseService> builder = serviceTarget.addService(moduleSpecServiceName(moduleDefinition.getModuleIdentifier(), 0), nextPhaseService);
        builder.install();
    }

    private static void installService(final ServiceTarget serviceTarget, final ModuleIdentifier moduleIdentifier, int phaseNumber, final Set<ModuleDependency> nextPhaseIdentifiers, final Set<ModuleIdentifier> nextAlreadySeen) {
        final ModuleResolvePhaseService nextPhaseService = new ModuleResolvePhaseService(moduleIdentifier, nextAlreadySeen, phaseNumber);
        ServiceBuilder<ModuleResolvePhaseService> builder = serviceTarget.addService(moduleSpecServiceName(moduleIdentifier, phaseNumber), nextPhaseService);
        for (ModuleDependency module : nextPhaseIdentifiers) {
            builder.addDependency(ServiceModuleLoader.moduleSpecServiceName(module.getIdentifier()), ModuleDefinition.class, new Injector<ModuleDefinition>() {

                ModuleDefinition definition;

                @Override
                public synchronized void inject(final ModuleDefinition o) throws InjectionException {
                    nextPhaseService.getModuleSpecs().add(o);
                    this.definition = o;
                }

                @Override
                public synchronized void uninject() {
                    nextPhaseService.getModuleSpecs().remove(definition);
                    this.definition = null;
                }
            });
        }
        builder.install();
    }

    @Override
    public void stop(final StopContext stopContext) {

    }

    @Override
    public ModuleResolvePhaseService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public Set<ModuleDefinition> getModuleSpecs() {
        return moduleSpecs;
    }

    public static ServiceName moduleSpecServiceName(ModuleIdentifier identifier, int phase) {
        if (!ServiceModuleLoader.isDynamicModule(identifier)) {
            throw ServerLogger.ROOT_LOGGER.missingModulePrefix(identifier, ServiceModuleLoader.MODULE_PREFIX);
        }
        return SERVICE_NAME.append(identifier.getName()).append(identifier.getSlot()).append("" + phase);
    }
}
