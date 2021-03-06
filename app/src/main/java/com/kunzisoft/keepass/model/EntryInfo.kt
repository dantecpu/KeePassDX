/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.model

import android.os.Parcel
import android.os.Parcelable
import com.kunzisoft.keepass.database.element.Attachment
import com.kunzisoft.keepass.database.element.Database
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.icon.IconImage
import com.kunzisoft.keepass.database.element.icon.IconImageStandard
import com.kunzisoft.keepass.database.element.security.ProtectedString
import com.kunzisoft.keepass.otp.OtpElement
import com.kunzisoft.keepass.otp.OtpEntryFields
import com.kunzisoft.keepass.otp.OtpEntryFields.OTP_TOKEN_FIELD
import kotlin.collections.ArrayList

class EntryInfo : Parcelable {

    var id: String = ""
    var title: String = ""
    var icon: IconImage = IconImageStandard()
    var username: String = ""
    var password: String = ""
    var creationTime: DateInstant = DateInstant()
    var modificationTime: DateInstant = DateInstant()
    var expires: Boolean = false
    var expiryTime: DateInstant = DateInstant.IN_ONE_MONTH
    var url: String = ""
    var notes: String = ""
    var customFields: List<Field> = ArrayList()
    var attachments: List<Attachment> = ArrayList()
    var otpModel: OtpModel? = null

    constructor()

    private constructor(parcel: Parcel) {
        id = parcel.readString() ?: id
        title = parcel.readString() ?: title
        icon = parcel.readParcelable(IconImage::class.java.classLoader) ?: icon
        username = parcel.readString() ?: username
        password = parcel.readString() ?: password
        creationTime = parcel.readParcelable(DateInstant::class.java.classLoader) ?: creationTime
        modificationTime = parcel.readParcelable(DateInstant::class.java.classLoader) ?: modificationTime
        expires = parcel.readInt() != 0
        expiryTime = parcel.readParcelable(DateInstant::class.java.classLoader) ?: expiryTime
        url = parcel.readString() ?: url
        notes = parcel.readString() ?: notes
        parcel.readList(customFields, Field::class.java.classLoader)
        parcel.readList(attachments, Attachment::class.java.classLoader)
        otpModel = parcel.readParcelable(OtpModel::class.java.classLoader) ?: otpModel
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(title)
        parcel.writeParcelable(icon, flags)
        parcel.writeString(username)
        parcel.writeString(password)
        parcel.writeParcelable(creationTime, flags)
        parcel.writeParcelable(modificationTime, flags)
        parcel.writeInt(if (expires) 1 else 0)
        parcel.writeParcelable(expiryTime, flags)
        parcel.writeString(url)
        parcel.writeString(notes)
        parcel.writeArray(customFields.toTypedArray())
        parcel.writeArray(attachments.toTypedArray())
        parcel.writeParcelable(otpModel, flags)
    }

    fun containsCustomFieldsProtected(): Boolean {
        return customFields.any { it.protectedValue.isProtected }
    }

    fun containsCustomFieldsNotProtected(): Boolean {
        return customFields.any { !it.protectedValue.isProtected }
    }

    fun containsCustomField(label: String): Boolean {
        return customFields.lastOrNull { it.name == label } != null
    }

    fun getGeneratedFieldValue(label: String): String {
        if (label == OTP_TOKEN_FIELD) {
            otpModel?.let {
                return OtpElement(it).token
            }
        }
        return customFields.lastOrNull { it.name == label }?.protectedValue?.toString() ?: ""
    }

    private fun addUniqueField(field: Field, number: Int = 0) {
        var sameName = false
        var sameValue = false
        val suffix = if (number > 0) "_$number" else ""
        customFields.forEach { currentField ->
            // Not write the same data again
            if (currentField.protectedValue.stringValue == field.protectedValue.stringValue) {
                sameValue = true
                return
            }
            // Same name but new value, create a new suffix
            if (currentField.name == field.name + suffix) {
                sameName = true
                addUniqueField(field, number + 1)
                return
            }
        }
        if (!sameName && !sameValue)
            (customFields as ArrayList<Field>).add(Field(field.name + suffix, field.protectedValue))
    }

    fun saveSearchInfo(database: Database?, searchInfo: SearchInfo) {
        searchInfo.otpString?.let { otpString ->
            // Replace the OTP field
            OtpEntryFields.parseOTPUri(otpString)?.let { otpElement ->
                if (title.isEmpty())
                    title = otpElement.issuer
                if (username.isEmpty())
                    username = otpElement.name
                // Add OTP field
                val mutableCustomFields = customFields as ArrayList<Field>
                val otpField = OtpEntryFields.buildOtpField(otpElement, null, null)
                if (mutableCustomFields.contains(otpField)) {
                    mutableCustomFields.remove(otpField)
                }
                mutableCustomFields.add(otpField)
            }
        } ?: searchInfo.webDomain?.let { webDomain ->
            // If unable to save web domain in custom field or URL not populated, save in URL
            val scheme = searchInfo.webScheme
            val webScheme = if (scheme.isNullOrEmpty()) "http" else scheme
            val webDomainToStore = "$webScheme://$webDomain"
            if (database?.allowEntryCustomFields() != true || url.isEmpty()) {
                url = webDomainToStore
            }
            else if (url != webDomainToStore){
                // Save web domain in custom field
                addUniqueField(Field(WEB_DOMAIN_FIELD_NAME,
                        ProtectedString(false, webDomainToStore)),
                        1 // Start to one because URL is a standard field name
                )
            }
        } ?: run {
            // Save application id in custom field
            if (database?.allowEntryCustomFields() == true) {
                searchInfo.applicationId?.let { applicationId ->
                    addUniqueField(Field(APPLICATION_ID_FIELD_NAME,
                            ProtectedString(false, applicationId))
                    )
                }
            }
        }
    }

    companion object {

        const val WEB_DOMAIN_FIELD_NAME = "URL"
        const val APPLICATION_ID_FIELD_NAME = "AndroidApp"

        @JvmField
        val CREATOR: Parcelable.Creator<EntryInfo> = object : Parcelable.Creator<EntryInfo> {
            override fun createFromParcel(parcel: Parcel): EntryInfo {
                return EntryInfo(parcel)
            }

            override fun newArray(size: Int): Array<EntryInfo?> {
                return arrayOfNulls(size)
            }
        }
    }
}
