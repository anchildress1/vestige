package dev.anchildress1.vestige.storage

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * Free-form, model-extracted tag (people / topics / activities / places) per
 * `concept-locked.md` §Schema. [name] is kebab-case lowercase per architecture-brief.md
 * §"Field placement rules".
 */
@Entity
class TagEntity(
    @Id var id: Long = 0,
    @Index @Unique var name: String = "",
    /** Count of entries currently linked to this tag — maintained by `:core-storage` writes. */
    var entryCount: Int = 0,
)
