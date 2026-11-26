package com.cryptotrading.common.mybatis.sample;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cryptotrading.common.mybatis.entity.BaseEntity;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sample_item")
public class SampleItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal quantity;

    public static SampleItem of(String name, String quantity) {
        SampleItem item = new SampleItem();
        item.setName(name);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }
}
