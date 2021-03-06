package com.seizedays.semall.order.services.impl;

import com.seizedays.semall.beans.OmsOrder;
import com.seizedays.semall.beans.OmsOrderItem;
import com.seizedays.semall.mq.ActiveMQUtil;
import com.seizedays.semall.order.mappers.OmsOrderItemMapper;
import com.seizedays.semall.order.mappers.OmsOrderMapper;
import com.seizedays.semall.services.OrderService;
import com.seizedays.semall.util.RedisUtil;
import org.apache.activemq.command.ActiveMQMapMessage;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import tk.mybatis.mapper.entity.Example;

import javax.jms.*;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    RedisUtil redisUtil;

    @Autowired
    OmsOrderMapper omsOrderMapper;

    @Autowired
    OmsOrderItemMapper omsOrderItemMapper;

    @Autowired
    ActiveMQUtil activeMQUtil;

    @Override
    public String checkTradeCode(Long memberId, String tradeCode) {

        String tradeKey = "user:" + memberId +":tradeCode";
        String tradeCodeFromCache = "";

        try(Jedis jedis = redisUtil.getJedis()) {
            tradeCodeFromCache = jedis.get(tradeKey);

            //使用lua脚本在发现key的同时删除key 防止并发订单攻击
            String script = "if redis.call('get', KEYS[1])==ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

            Long eval = (long) jedis.eval(script, Collections.singletonList(tradeKey), Collections.singletonList(tradeCode));

            if (null != eval && eval != 0L){
                return "success";
            }else {
                return "fail";
            }

        }


    }

    @Override
    public String generateTradeCode(Long memberId) {

        String tradeKey = "user:" + memberId +":tradeCode";
        String tradeCode = UUID.randomUUID().toString();


        try(Jedis jedis = redisUtil.getJedis()) {

            jedis.setex(tradeKey, 15*60, tradeCode);

        }
        return tradeCode;
    }

    @Override
    public void saveOrder(OmsOrder omsOrder) {
        //保存订单表
        omsOrderMapper.insertSelective(omsOrder);
        Long orderId = omsOrder.getId();
        System.out.println(orderId);
        //保存订单详情
        List<OmsOrderItem> omsOrderItems = omsOrder.getOmsOrderItems();
        for (OmsOrderItem omsOrderItem : omsOrderItems) {
            omsOrderItem.setOrderId(orderId);
            omsOrderItemMapper.insertSelective(omsOrderItem);
        }

        //删除购物车数据
        // cartService.delCart();


    }

    @Override
    public OmsOrder getOrderByOutTradeNo(String outTradeNo) {
        OmsOrder omsOrder = new OmsOrder();
        omsOrder.setOrderSn(outTradeNo);
        return omsOrderMapper.selectOne(omsOrder);
    }

    @Override
    public void updateOrder(OmsOrder omsOrder) {
        omsOrder.setStatus(1);
        Example example = new Example(OmsOrder.class);
        example.createCriteria().andEqualTo("orderSn", omsOrder.getOrderSn());

        Connection connection = null;
        Session session = null;
        try {
            connection = activeMQUtil.getConnectionFactory().createConnection();
            session = connection.createSession(true, Session.SESSION_TRANSACTED);
        } catch (JMSException ex) {
            ex.printStackTrace();
        }

        if (session != null) {
            try {
                omsOrderMapper.updateByExampleSelective(omsOrder,example);

                //发送订单已支付的消息队列 提供给库存系统消费
                Queue orderPayQueue = session.createQueue("ORDER_PAY_QUEUE");
                MessageProducer producer = session.createProducer(orderPayQueue);

                MapMessage mapMessage = new ActiveMQMapMessage();
                producer.send(mapMessage);
                session.commit();
            } catch (JMSException e2) {
                //回滚
                try {
                    session.rollback();
                } catch (JMSException ex) {
                    ex.printStackTrace();
                }
            } finally {
                try {
                    connection.close();
                } catch (JMSException ex) {
                    ex.printStackTrace();
                }
            }
        }

    }


}
