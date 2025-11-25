package fun.luqing.dmws.common.converter;

import fun.luqing.dmws.enums.PlaceEnum;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PlaceEnumConverter implements AttributeConverter<PlaceEnum, String> {

    @Override
    public String convertToDatabaseColumn(PlaceEnum attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public PlaceEnum convertToEntityAttribute(String dbData) {
        return dbData != null ? PlaceEnum.valueOf(dbData) : null;
    }
}
