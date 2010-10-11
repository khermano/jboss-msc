/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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

package org.jboss.msc.service;

/**
 * An OptionalDependency.<br>This class establishes a transitive dependency relationship between the
 * dependent and the real dependency. The intermediation performed by this class adds the required optional
 * behavior to the dependency relation, by:
 * <ul>
 * <li> notifies the dependent that it is in the UP state when the real dependency is unresolved or uninstalled</li>
 * <li> once the real dependency is installed, if there is a demand previously added by the dependent, this dependency
 *      does not start forwarding the notifications to the dependent, meaning that the dependent won't even be aware
 *      that the dependency is down</li>
 * <li> waits for the dependency to be installed and the dependent to be inactive, so it can finally start forwarding
 *      notifications in both directions (from dependency to dependent and vice-versa)</li>
 * </ul>
 * 
 * @author <a href="mailto:flavia.rainone@jboss.com">Flavia Rainone</a>
 */
class OptionalDependency implements Dependency, Dependent {
    
    /**
     * One of the states of a dependency from the dependent point of view (i.e., based on notifications made by the
     * dependency).
     *
     */
    private static enum DependencyState {
        /**
         * The dependency is missing; means the last notification received by this {@code OptionalDependency} (as a
         * dependent of the real dependency) is {@link #dependencyUninstalled}.
         */
        MISSING,
        /**
         * The dependency is installed, but is not up. This is the initial state of the dependency. Also, if any
         * notification has been made by the dependency, this will be the dependency state if the last notification
         * received is {@link #dependencyInstalled}, {@link #dependencyDown}, or {@link #dependencyRetrying}.
         */
        INSTALLED,
        /**
         * The dependency failed; means the last notification received by this {@code OptionalDependency} (as a
         * dependent of the real dependency) is {@link #dependencyFailed}.
         */
        FAILED,
        /**
         * The dependency is up; means the last notification received by this {@code OptionalDependency} (as a
         * dependent of the real dependency) is {@link #dependencyUp}.
         */
        UP}

    /**
     * The real dependency.
     */
    private final Dependency optionalDependency;

    /**
     * The {@link #optionalDependency} state, based on notifications that {@code optionalDependency} made to this 
     * dependent.
     */
    private DependencyState dependencyState;

    /**
     * Indicates whether a transitive dependency is missing.
     */
    private boolean transitiveDependencyMissing;

    /**
     * The dependent on this optional dependency
     */
    private Dependent dependent;

    /**
     * Indicates if this dependency has been demanded by the dependent 
     */
    private boolean demandedByDependent;

    /**
     * Indicates if notification should take place
     */
    boolean forwardNotifications;

    OptionalDependency(Dependency optionalDependency) {
        this.optionalDependency = optionalDependency;
        dependencyState = DependencyState.INSTALLED;
        optionalDependency.addDependent(this);
    }

    @Override
    public void addDependent(Dependent dependent) {
        assert !lockHeld();
        assert !lockHeldByDependent(dependent);
        final boolean notifyDependent;
        final DependencyState currentDependencyState;
        synchronized (this) {
            if (this.dependent != null) {
                throw new IllegalStateException("Optional dependent is already set");
            }
            this.dependent = dependent;
            notifyDependent = forwardNotifications = dependencyState.compareTo(DependencyState.INSTALLED) >= 0;
            currentDependencyState = dependencyState;
        }
        dependent.dependencyInstalled();
        if (notifyDependent) {
            switch (currentDependencyState) {
                case FAILED:
                    dependent.dependencyFailed();
                    break;
                case UP:
                    dependent.dependencyUp();
            }
            if (transitiveDependencyMissing) {
                dependent.transitiveDependencyUninstalled();
            }
        }
        else {
            dependent.dependencyUp();
        }
    }

    @Override
    public void removeDependent(Dependent dependent) {
        assert !lockHeld();
        assert !lockHeldByDependent(dependent);
        synchronized (this) {
            dependent = null;
            forwardNotifications = false;
        }
        optionalDependency.removeDependent(this);
    }

    @Override
    public void addDemand() {
        assert ! lockHeld();
        final boolean notifyOptionalDependency;
        synchronized (this) {
            demandedByDependent = true;
            notifyOptionalDependency = forwardNotifications;
        }
        if (notifyOptionalDependency) {
            optionalDependency.addDemand();
        }
    }

    @Override
    public void removeDemand() {
        assert ! lockHeld();
        final boolean startNotifying;
        final boolean notifyOptionalDependency;
        final DependencyState currentDependencyState;
        final boolean transitiveDependencyMissing;
        synchronized (this) {
            demandedByDependent = false;
            currentDependencyState = dependencyState;
            transitiveDependencyMissing = this.transitiveDependencyMissing;
            if (forwardNotifications) {
                notifyOptionalDependency = true;
                startNotifying = false;
            } else {
                notifyOptionalDependency = false;
                startNotifying = forwardNotifications = (dependencyState.compareTo(DependencyState.INSTALLED) >= 0);
            }
        }
        if (startNotifying) {
            switch (currentDependencyState) {
                case INSTALLED:
                    dependent.dependencyDown();
                    break;
                case FAILED:
                    dependent.dependencyFailed();
                    break;
            }
            if (transitiveDependencyMissing) {
                dependent.transitiveDependencyUninstalled();
            }
        } else if (notifyOptionalDependency) {
            optionalDependency.removeDemand();
        }
    }

    @Override
    public void dependentStarted() {
        assert ! lockHeld();
        final boolean notifyOptionalDependency;
        synchronized (this) {
            notifyOptionalDependency = forwardNotifications;
        }
        if (notifyOptionalDependency) {
            optionalDependency.dependentStarted();
        }
    }

    @Override
    public void dependentStopped() {
        assert ! lockHeld();
        final boolean notifyOptionalDependency;
        synchronized (this) {
            notifyOptionalDependency = forwardNotifications;
        }
        if (notifyOptionalDependency) {
            optionalDependency.dependentStopped();
        }
    }

    @Override
    public Object getValue() throws IllegalStateException {
        assert ! lockHeld();
        final boolean retrieveValue;
        synchronized (this) {
            retrieveValue = forwardNotifications;
        }
        return retrieveValue? optionalDependency.getValue(): null;
    }

    @Override
    public void dependencyInstalled() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        final boolean notifyTransitiveDependencyUninstalled;
        synchronized (this) {
            dependencyState = DependencyState.INSTALLED;
            forwardNotifications = notifyOptionalDependent = !demandedByDependent;
            notifyTransitiveDependencyUninstalled = transitiveDependencyMissing;
        }
        if (notifyOptionalDependent) {
            // need to update the dependent by telling it that the dependency is down
            dependent.dependencyDown();
            if (notifyTransitiveDependencyUninstalled) {
                dependent.transitiveDependencyUninstalled();
            }
        }
    }

    @Override
    public void dependencyUninstalled() {
        assert ! lockHeld();
        final boolean notificationsForwarded;
        final DependencyState oldDependencyState;
        final boolean notifyTransitiveDependencyInstalled;
        synchronized (this) {
            notificationsForwarded = forwardNotifications;
            forwardNotifications = false;
            oldDependencyState = dependencyState;
            dependencyState = DependencyState.MISSING;
            notifyTransitiveDependencyInstalled = transitiveDependencyMissing;
        }
        if (notificationsForwarded) {
            // need to undo the notifications that were forwarded
            // to the dependent point of view, from now on this dependency is up and running, without failures
            if (notifyTransitiveDependencyInstalled) {
                dependent.transitiveDependencyInstalled();
            }
            if (oldDependencyState == DependencyState.FAILED) {
                dependent.dependencyRetrying();
            }
            // now that the optional dependency is uninstalled, we enter automatically the up state
            dependent.dependencyUp();
        }
    }

    @Override
    public void dependencyUp() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        synchronized (this) {
            dependencyState = DependencyState.UP;
            notifyOptionalDependent = forwardNotifications;
        }
        if (notifyOptionalDependent) {
            dependent.dependencyUp();
        }
    }

    @Override
    public void dependencyDown() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        synchronized (this) {
            dependencyState = DependencyState.INSTALLED;
            notifyOptionalDependent = forwardNotifications;
        }
        if (notifyOptionalDependent) {
            dependent.dependencyDown();
        }
    }

    @Override
    public void dependencyFailed() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        synchronized (this) {
            dependencyState = DependencyState.FAILED;
            notifyOptionalDependent = forwardNotifications;
        }
        if (notifyOptionalDependent) {
            dependent.dependencyFailed();
        }
    }

    @Override
    public void dependencyRetrying() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        synchronized (this) {
            dependencyState = DependencyState.INSTALLED;
            notifyOptionalDependent = forwardNotifications;
        }
        if (notifyOptionalDependent) {
            dependent.dependencyRetrying();
        }
    }

    @Override
    public void transitiveDependencyInstalled() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        synchronized (this) {
            notifyOptionalDependent = forwardNotifications;
            transitiveDependencyMissing = false;
        }
        if (notifyOptionalDependent) {
            dependent.transitiveDependencyInstalled();
        }
    }

    @Override
    public void transitiveDependencyUninstalled() {
        assert ! lockHeld();
        final boolean notifyOptionalDependent;
        synchronized (this) {
            notifyOptionalDependent = forwardNotifications;
            transitiveDependencyMissing = true;
        }
        if (notifyOptionalDependent) {
            dependent.transitiveDependencyUninstalled();
        }
    }

    /**
     * Determine whether the lock is currently held.
     *
     * @return {@code true} if the lock is held
     */
    boolean lockHeld() {
        return Thread.holdsLock(this);
    }

    /**
     * Determine whether the dependent lock is currently held.
     *
     * @return {@code true} if the lock is held
     */
    boolean lockHeldByDependent(Dependent dependent) {
        return Thread.holdsLock(dependent);
    }
}
