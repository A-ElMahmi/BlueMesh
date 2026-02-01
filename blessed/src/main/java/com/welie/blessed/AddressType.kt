package com.welie.blessed


enum class AddressType(val value: Int) {
    /** Address type is public and registered with the IEEE. */
    PUBLIC(0),

    /** Address type is random. */
    RANDOM(1),

    /** Address type is unknown. */
    UNKNOWN(0xFFFF),

    /** Address type is anonymous (for advertisements). */
    ADDRESS_TYPE_ANONYMOUS(0xFF);

    companion object {
        fun fromValue(value: Int): AddressType {
            for (type in AddressType.entries) {
                if (type.value == value) {
                    return type
                }
            }
            return UNKNOWN
        }
    }
}