package net.javacrumbs.shedlock.provider.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * HazelcastLockProvider.
 * <p>
 * Implementation of {@link LockProvider} using Hazelcast for store and share locks informations and mechanisms between a cluster members
 * <p>
 * Below, the mechanims :
 * - The Lock, an instance of {@link HazelcastLock}, is obtained / created when :
 * - the lock is not not already locked by other process (lock - referenced by its name - is not present in the Hazelcast locks store OR unlockable)
 * - the lock is expired : {@link Instant#now()} &gt; {@link HazelcastLock#unlockTime} where unlockTime have by default the same value of {@link HazelcastLock#lockAtMostUntil}
 * and can have the value of {@link HazelcastLock#lockAtLeastUntil} if unlock action is used
 * - expired object is removed
 * - the lock is owned by not available member of Hazelcast cluster member
 * - no owner objectis removed
 * - Unlock action :
 * - removes lock object when {@link HazelcastLock#lockAtLeastUntil} is not come
 * - override value of {@link HazelcastLock#unlockTime} with {@link HazelcastLock#lockAtLeastUntil} (its default value is the same of {@link HazelcastLock#lockAtLeastUntil})
 */
public class HazelcastLockProvider implements LockProvider {

    private static final Logger log = LoggerFactory.getLogger(HazelcastLockProvider.class);

    static final String LOCK_STORE_KEY_DEFAULT = "shedlock_storage";

    /**
     * Key used for get the lock container (an {@link IMap}) inside {@link #hazelcastInstance}.
     * By default : {@link #LOCK_STORE_KEY_DEFAULT}
     */
    private final String lockStoreKey;

    /**
     * Instance of the Hazelcast engine used by the application.
     */
    private HazelcastInstance hazelcastInstance;

    /**
     * Instanciate the provider.
     *
     * @param hazelcastInstance The Hazelcast engine used by the application.
     */
    public HazelcastLockProvider(final HazelcastInstance hazelcastInstance) {
        this(hazelcastInstance, LOCK_STORE_KEY_DEFAULT);
    }

    /**
     * Instanciate the provider.
     *
     * @param hazelcastInstance The Hazelcast engine used by the application
     * @param lockStoreKey      The key where the locks store is associate {@link #hazelcastInstance} (by default {@link #LOCK_STORE_KEY_DEFAULT}).
     */
    public HazelcastLockProvider(final HazelcastInstance hazelcastInstance, final String lockStoreKey) {
        this.hazelcastInstance = hazelcastInstance;
        this.lockStoreKey = lockStoreKey;
    }

    @Override
    public Optional<SimpleLock> lock(final LockConfiguration lockConfiguration) {
        log.trace("lock - Attempt : {}", lockConfiguration);
        final Instant now = Instant.now();
        final String lockName = lockConfiguration.getName();
        final IMap<String, HazelcastLock> store = getStore();
        try {
            // lock the map key entry
            store.lock(lockName);
            // just one thread at a time, in the cluster, can run this code
            // each thread waits until the lock to be unlock
            if (tryLock(lockConfiguration, now)) {
                return Optional.of(() -> unlock(lockName));
            }
        } finally {
            // released the map lock for the others threads
            store.unlock(lockName);
        }
        return Optional.empty();
    }

    private boolean tryLock(final LockConfiguration lockConfiguration, final Instant now) {
        final String lockName = lockConfiguration.getName();
        final HazelcastLock lock = getLock(lockName);
        if (isUnlocked(lock)) {
            log.debug("lock - lock obtained, it wasn't locked : conf={}", lockConfiguration);
            addNewLock(lockConfiguration);
            return true;
        } else if (isExpired(lock, now)) {
            log.debug("lock - lock obtained, it was locked but expired : oldLock={};  conf={}", lock, lockConfiguration);
            replaceLock(lockConfiguration, lockName);
            return true;
        } else if (isLockedByUnavailableMemberOfCluster(lock)) {
            log.debug("lock - lock obtained, it was locked by an available member of cluster :  oldLock={};  conf={}", lock, lockConfiguration);
            replaceLock(lockConfiguration, lockName);
            return true;
        } else {
            log.debug("lock - already locked : currentLock={};  conf={}", lock, lockConfiguration);
            return false;
        }
    }

    private void replaceLock(LockConfiguration lockConfiguration, String lockName) {
        removeLock(lockName);
        addNewLock(lockConfiguration);
    }

    private IMap<String, HazelcastLock> getStore() {
        return hazelcastInstance.getMap(lockStoreKey);
    }

    HazelcastLock getLock(final String lockName) {
        final IMap<String, HazelcastLock> store = getStore();
        return store.get(lockName);
    }

    private void removeLock(final String lockName) {
        final IMap<String, HazelcastLock> store = getStore();
        store.delete(lockName);
        log.debug("lock store - lock deleted : {}", lockName);
    }

    private void addNewLock(final LockConfiguration lockConfiguration) {
        final String localMemberUuid = getLocalMemberUuid();
        final HazelcastLock lock = HazelcastLock.fromLockConfiguration(lockConfiguration, localMemberUuid);
        final String lockName = lockConfiguration.getName();
        putToStore(lock, lockName);
        log.debug("lock store - new lock added : {}", lock);
    }

    private void putToStore(HazelcastLock lock, String lockName) {
        getStore().put(lockName, lock);
    }

    private String getLocalMemberUuid() {
        return hazelcastInstance.getCluster().getLocalMember().getUuid();
    }

    boolean isUnlocked(final HazelcastLock lock) {
        return lock == null;
    }

    boolean isExpired(final HazelcastLock lock, final Instant now) {
        final Instant unlockTime = lock.getUnlockTime();
        return now.isAfter(unlockTime);
    }

    boolean isLockedByUnavailableMemberOfCluster(final HazelcastLock lock) {
        final String memberUuid = lock.getClusterMemberUuid();
        final boolean memberIsUp = hazelcastInstance.getCluster().getMembers().stream().anyMatch(member -> member.getUuid().equals(memberUuid));
        return !memberIsUp;
    }

    /**
     * Unlock the lock with its name.
     *
     * @param lockName the name of the lock to unlock.
     */
    private void unlock(final String lockName) {
        log.trace("unlock - attempt : {}", lockName);
        final Instant now = Instant.now();
        final IMap<String, HazelcastLock> store = getStore();
        try {
            store.lock(lockName);
            final HazelcastLock lock = getLock(lockName);
            unlockProperly(lock, now);
        } finally {
            store.unlock(lockName);
        }
    }

    private void unlockProperly(final HazelcastLock lock, final Instant now) {
        if (isUnlocked(lock)) {
            log.debug("unlock - it is already unlocked");
            return;
        }
        final String lockName = lock.getName();
        final Instant lockAtLeastInstant = lock.getLockAtLeastUntil();
        if (!now.isBefore(lockAtLeastInstant)) {
            removeLock(lockName);
            log.debug("unlock - done : {}", lock);
        } else {
            log.debug("unlock - it doesn't unlock, least time is not passed : {}", lock);
            lock.setUnlockTime(lockAtLeastInstant);
            putToStore(lock, lockName);
        }

    }
}
