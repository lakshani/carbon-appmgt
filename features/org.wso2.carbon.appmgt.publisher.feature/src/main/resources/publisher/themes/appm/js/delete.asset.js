/*
 Description: The script is used to delete an asset
 Filename: delete.asset.js
 Created Date: 22/7/2014
 */

$(function () {

    $('.btn-delete').on('click', function (e) {


        //The type of asset
        var type = $('#meta-asset-type').val();
        var provider = $(this).data("provider");
        var name = $(this).data("name");
        var version = $(this).data("version");
        var parent = $(this).parent();
        var btnDelete = $(this);

        var status = isExistInExternalStore(provider, name, version);
        if(status) {
            var msg = "This app is published to one or more external stores .\n" +
                "Please remove this app from external stores before delete";
            var head = "Delete Failed";
            showMessageModel(msg, head, type);
            return false;
        }

        $(parent).children().attr('disabled', true);
        var id = $(this).attr('data-app-id');

        e.preventDefault();
        e.stopPropagation();

        var confirmationMsg;
        var hasSubscription = hasSubscriptions(type, id);
        if (hasSubscription) {
            confirmationMsg = "This Application already has subscriptions. Are you sure you want to delete this app?";
        } else {
            confirmationMsg = "Are you sure you want to delete this app?";
        }

        var confirmDel = confirm(confirmationMsg);
        if (confirmDel == true) {
            $.ajax({
                url: caramel.context + '/api/asset/delete/' + type + '/' + id,
                type: 'POST',
                contentType: 'application/json',
                success: function (response) {
                    var result = response;
                    if (result.isDeleted) {
                        btnDelete.closest('tr').remove();
                        //if no apps, reload page
                        //if(btnDelete.closest('tbody').find('tr').length == 0) {
                        //    window.location = caramel.context + '/assets/' + type + '/';
                        //}
                    } else if (result.isDeleted == false) {
                        showDeleteModel(result.message, result.message, type);
                        $(parent).children().attr('disabled', false);
                    } else {
                        showDeleteModel(result.message, result.message, type);
                        $(parent).children().attr('disabled', false);
                    }
                },
                error: function (response) {
                    showDeleteModel("Asset is not successfully deleted", "Deletion Failed", type);
                    $(parent).children().attr('disabled', false);
                }
            });
        } else {
            $(parent).children().attr('disabled', false);
        }

    });

    function hasSubscriptions(asset, id) {
        var hasSubscription = false;
        $.ajax({
                   url: caramel.context + '/api/lifecycle/subscribe/' + asset + '/' + id,
                   type: 'GET',
                   async: false,
                   success: function (response) {
                       hasSubscription = response.subscribed;
                   }
               });
        return hasSubscription;
    }

    var showDeleteModel = function (msg, head, type) {

        $('#messageModal2').html($('#confirmation-data1').html());
        $('#messageModal2 h3.modal-title').html((head));
        $('#messageModal2 div.modal-body').html('\n\n' + (msg) + '</b>');
        $('#messageModal2 a.btn-other').html('OK');
        $('#messageModal2').modal();
        $("#messageModal2").on('hidden.bs.modal', function () {
            window.location = caramel.context + '/assets/' + type + '/';
        });

    };

    function isExistInExternalStore(provider, name, version) {
        var publishedInExternalStores = false;
        $.ajax({
            async: false,
            url: caramel.context + '/api/asset/get/external/stores/webapp/' + provider + '/' + name + '/' + version,
            type: 'GET',
            processData: true,
            success: function (response) {
                if (!response.error) {
                    var appStores = response.appStores;

                    if (appStores != null && appStores != undefined) {
                        for (var i = 0; i < appStores.length; i++) {
                            if (appStores[i].published) {
                                publishedInExternalStores = true;
                                break;
                            }
                        }
                    }
                }
                return publishedInExternalStores;

            },
            error: function (response) {

            }
        });

        return publishedInExternalStores;
    }
});