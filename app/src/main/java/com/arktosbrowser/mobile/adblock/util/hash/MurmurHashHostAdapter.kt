package com.arktosbrowser.mobile.adblock.util.hash

import com.arktosbrowser.mobile.database.adblock.Host
import java.io.Serializable

/**
 * A [HashingAlgorithm] of type [Host] backed by the [MurmurHash].
 */
class MurmurHashHostAdapter : HashingAlgorithm<Host>, Serializable {

    override fun hash(item: Host): Int = MurmurHash.hash32(item.name)

}
